# Error handling: unwind-protect + condition objects + handler-case + restarts

**Backend contract: interpreter, JVM and both wasm-GC backends (Preview 1 + `--component`, incl.
serve) are full; only `--no-gc` rejects `unwind-protect` / `handler-case` / `ignore-errors` at
compile time** (no condition objects in its value model).

wasm-GC catching uses the WebAssembly exception-handling proposal and is GATED: only a program
containing one of the three catching forms is compiled in **EH mode** (one `$lisp-cond` tag,
`try_table`/`throw`) and only such a program needs wasmtime 37+. Anything else is byte-identical to
a build that never knew about EH.

- **A NON-LOCAL EXIT is not a condition**: it passes through a `handler-case` uncaught while still
  running every `unwind-protect` cleanup -- cross-lambda `return-from`/`go` and `catch`/`throw`
  share one exit channel ([do-return-block.md](do-return-block.md), which owns the
  `ctx.blockExitTag`/`blockExitChannel` gate).
- **The three-point catchability spectrum**: interpreter catches `LispEvalException` only, JVM any
  `RuntimeException`, wasm-GC only `$lisp-cond` throws -- raw traps there (failed ref.cast, integer
  divide by zero, `unreachable`) are uncatchable and skip unwind-protect cleanups.

## Phase 1 -- unwind-protect
`LispEvaluator.evalUnwindProtect` (try/finally over both Java unwind channels, `LispEvalException`
and `BlockReturnSignal`); `JvmUnwindProtectCompiler` over `ByteCodeWriter.writeExceptionTable` +
`ExceptionTableEntry`; `WasmUnwindProtectCompiler` (`block $u (result exnref)` +
`try_table (catch_all_ref $u)`, landing = cleanups over the exnref then `throw_ref`). A cleanup that
signals replaces the pending unwind (CL: newer exit wins).

- JVM emitters stay frame-free (raw output is version-50 and verifies handlers without a
  StackMapTable); version-61 handler frames are synthesized offline by `StackMapAugmenter`
  ([stackmap-augmenter.md](stackmap-augmenter.md)). Each `Ctx` carries a per-method
  `exceptionTable` emitted by the four method-writing sites; `JvmClassShaker` is
  exception-table-aware.
- **The `return` channel is a plain GOTO/br and would skip the cleanups**, so `Ctx.unwindScopes`
  records active scopes. JVM: `JvmReturnCompiler` compiles every ESCAPED scope's cleanups inline
  before its GOTO (escaped = `scope.blockDepth >= blockTargets.size()`, innermost first), and those
  inlined ranges are `holes` the scope's own exception entries exclude -- a throw from an inlined
  cleanup must not re-enter its own handler but must still reach OUTER ones. WASM: each scope pushes
  an `UnwindScope{cleanupForms, blockDepth, trampolineDepth}` and `WasmReturnCompiler` branches to a
  trampoline block emitted lexically OUTSIDE the try_table (the structural equivalent of `holes`),
  cascading innermost-first.
- **A cleanup's VALUES are discarded, its value COUNT included**: the cleanup sequence is bracketed
  by a save/restore of the `%mv-spill` channel on all three backends, and the save lives in each
  backend's SHARED cleanup emitter so `return`/`go`-inlined copies get it too
  ([multiple-values.md](multiple-values.md)).
- **with-\* retrofit**: `expandWithOpenFile` / `expandWithOutputToString` /
  `expandWithInputFromString` / the three usocket `with-*` take a `boolean unwindProtect` (default
  true); WASM call sites pass `false`, so interpreter/JVM close on EVERY exit, WASM on normal exit
  only.
- Known limit: the compile-path special-`let` restore on a `return` across the binding
  ([dynamic-special-variables.md](dynamic-special-variables.md)).

## Phase 2 -- condition objects
A condition is a CLOS-subset instance ([instance-syntax.md](instance-syntax.md)):
`(%obj-new '%class-<name> slots...)`, printed `#<NAME :SLOT value ...>`, NOT a list.

- `ClosRegistry`'s constructor seeds the hierarchy from `CONDITION_SEEDS`, ONE static list with two
  consumers that must keep READING it, never a copy: the constructor, and
  `PackageRegistry.CL_CONDITION_TYPES` = `ClosRegistry.CONDITION_CLASS_NAMES`, which makes every
  seeded name a `cl` symbol ([packages.md](packages.md)).
- Four classes carry `format-control`/`format-arguments` beyond CLHS's slot lists -- `type-error`,
  `arithmetic-error`, `unbound-variable`, `undefined-function` -- because that pair is how a
  BUILT-IN error carries its message. `simple-type-error` therefore adds nothing, so both keep their
  old `%obj-ref` indexes.
- `define-condition` = `defineConditionToDefclass` -> `expandDefclass` (top-level-only on the
  compile path); `(:report x)` registered (string or lambda AST), `:documentation` dropped. **Lite
  multiple parents**: the FIRST parent provides the slot layout, the rest join the ancestor set only
  (`registerExtraAncestors`).
- `error`/`warn`/`signal` share `expandSignalDesignator`: a string designator takes the LEGACY
  `(%error message)` path (byte-identical output); a quoted-type designator builds the instance via
  the registry slot layout (`buildTypedConstruct`) and signals `(%error-cond instance message)`; an
  object designator dispatches at runtime. A literal `(make-condition 'type ...)` argument re-routes
  through the typed path.
- **A datum that is a STRING at run time is a format control and the arguments after it are its
  format arguments**: `expandObjectSignal`'s string arm renders `(%fmt-render datum (list ...))`,
  fed by three callers. Argument forms are evaluated only on that arm, so every datum-only call keeps
  its previous expansion byte for byte. **The rendering is EAGER**: the instance carries rendered
  text in `format-control` and nil `format-arguments`. **Re-evaluate if** the renderer becomes free.
- The lite `#'error`/`#'warn`/`#'signal`/`#'cerror` WRAPPERS forward the datum only
  (`BuiltinFunctionWrappers.SIGNAL_FUNCTIONS`), so `(apply #'error c '(1 2))` drops the arguments on
  the compiled backends -- documented lite semantics, as for initargs.
- Channels: interpreter `LispEvalException` carries a nullable `condition()`; JVM `%error-cond`
  stores the instance into the emitted `private static ThreadLocal _condTl` and throws
  `RuntimeException(message)` (that field plus `_hcDepthTl` emitted only when used,
  `JvmLispCompiler.ConditionChannel`); WASM `%error-cond` traps like `%error`.
- `makeTypeTest` takes a `ClosRegistry` and has a class branch (descendant-tag membership, `equal`
  on the car -- WASM content-safe). `with-slots` is read-only; assignment does NOT write back.

## Phase 3 -- handler-case / ignore-errors
Surface: `(handler-case expr (type ([var]) body...)... [(:no-error ([var]) body...)])`; clause types
are `makeHandlerTypeTest` = `makeTypeTest` + an exact-tag fallback for unknown names. A
condition-less throw is caught as a synthesized `simple-error` with the message in slot 1. No match
-> rethrow (the JVM rethrow RESTORES `_condTl` first). `:no-error` runs on normal completion OUTSIDE
the handler. `ignore-errors` = `expandIgnoreErrors` over `(error (c) (values nil c))`.

- **Interpreter** `evalHandlerCase`: `try/catch (LispEvalException)`, `BlockReturnSignal` passing
  through. A per-evaluator `ThreadLocal<ArrayDeque<List<LispVal>>> handlerCaseTypes` holds every
  established handler-case's clause TYPE SPECIFIERS, and `%signal-cond` raises only when some active
  clause type MATCHES (`anyHandlerCaseMatches`). The built-in seam in `LispEvaluator.apply` wraps an
  escaping `IndexOutOfBounds`/`NegativeArraySize`/`Arithmetic`/`ClassCast` into a `LispEvalException`,
  so `aref` out of range and `(make-array -1)` are catchable here too.
- **JVM** `JvmHandlerCaseCompiler` and **WASM** `WasmHandlerCaseCompiler` share the layout: a
  catch-any / `try_table (catch $lisp-cond $h)` region over the protected expression whose landing
  synthesizes the simple-error (JVM from `Throwable.getMessage()`, quote-framed; wasm from the
  payload cdr, already a quote-framed Lisp string) and then compiles clause tests and bodies as
  ORDINARY Lisp over a `__hc_cond$<slot>` pseudo-local. `JvmSignalCondCompiler` checks `_hcDepthTl`
  (null = 0); the wasm depth global is inc/dec'd around the region. A `return` inside decrements the
  depth through the `UnwindScope` cleanup channel, the internal `%hc-depth-dec` form.
- **The JVM operand-stack spill (why handler-case works in an ARGUMENT position).** Unlike
  unwind-protect (handler ends in ATHROW, never rejoins), the handler MERGES back into the normal
  path -- and the JVM discards the operand stack on handler entry, so the two edges disagree by
  exactly the operands the ENCLOSING form had evaluated and the class does not verify.
  `Ctx.spillOperandStack()` saves live operands into fresh locals BEFORE the protected region and
  `Spill.restore` reloads them past the merge; a statement-position handler-case spills nothing and
  stays byte-identical. Liveness comes from `am.ik.jvm.OperandStack`, a typed model fed by
  `Ctx.emit`/`emitU2` that also supplies a real `max_stack` and raises on a merge-point mismatch
  rather than writing an unverifiable class. **An object under construction (`new`, pre-`<init>`)
  can never be spilled** -- tagged `Slot.UNINIT` and rejected. A `return` escaping a spilled region
  reloads from the outermost escaped `SpillScope` (`JvmReturnCompiler.emitStackUnwind`).
- `FreeVarAnalyzer` learned `handler-case` (clause var BOUND in the clause body), `ignore-errors`
  and `with-slots`.

## WASM EH-mode specifics
- **The gate** (`WasmLispCompiler.compile`): the program (post pre-passes, libraries spliced) is
  scanned for `handler-case`/`ignore-errors`/`unwind-protect` head symbols. Only then: the tag
  section (id 13, between memory and global) with ONE tag `$lisp-cond` whose type reuses
  `TYPE_PRINT_VAL` (`((ref null eq)) -> ()`), the handler-depth global (a `(mut i32)` appended AFTER
  the user globals, `Ctx.ehDepthGlobalIndex`), the throw path and the entry wrappers. Byte-identity
  without the forms is stash-dance proven across P1 / component / http-client / sockets / serve /
  --optimize / --dynamic / --no-wasi exports / --no-gc.
- **Throw path** (`WasmErrorCompiler`): in EH mode `%error`/`%error-cond` build the payload cons
  `(condition-instance . message-string)` (instance nil for plain `%error`) and `throw $lisp-cond`;
  outside EH mode they stay a bare `unreachable` without evaluating anything. `throw` is
  stack-polymorphic like `unreachable`, so call sites are unchanged.
- **Top-level trap shape** (`WasmEmitHelper.emitCatchAllPrologue/Epilogue`): every export wrapper
  body (incl. serve's `%http-dispatch`) runs inside `block` + `try_table (catch_all)` landing on
  `unreachable`, the normal path `return`ing from INSIDE the try_table. The ENTRY function
  (`_start`/`run`) uses the reporting variant instead.
- **Walkers**: `WasmSections.scanInstr` (shared by `WasmImportInjector`) knows `throw` (0x08, tag
  immediate), `throw_ref` (0x0A), `try_table` (0x1F, blocktype + catch-clause vector) and the
  `exnref` valtype (0x69); tags are their own index space so function renumbering is unaffected. P1
  EH + `--optimize` compose. Component path: core-module-internal only. V8 hosts: exnref is
  default-on in Chrome 137+ / Node 24+; Node 22 needs `--experimental-wasm-exnref`.
- **usocket typed conditions**: `usocket.lisp` defines the hierarchy and wraps
  `socket-connect`/`socket-listen`/`socket-accept` bodies in `(usocket::%usock-guard form)`,
  expanded per backend (`expandUsocketGuard`): interpreter/JVM = `handler-case` +
  `usocket::%usock-resignal` (always `usocket:socket-error`), WASM = pass-through. The shim source
  is parsed ONCE and cached for all backends, so the branch cannot be a reader feature.

## The read family signals a TYPED end-of-file
`read-char` / `read-byte`, and `read-line` with an explicit non-nil `eof-error-p`, signal the SEEDED
`end-of-file` class on all four backends (`ClosRegistry` seeds its `:report`, `END_OF_FILE_MESSAGE`).

- Interpreter: `Environment.endOfFile()` throws carrying `ClosRegistry.newEndOfFileCondition()`, a
  static factory -- sound because the class is SEEDED (same slot-less layout in every registry) and
  `handler-case` dispatches on the instance TAG, not layout identity.
- Compiled: one shared CALL-SITE lowering, `LispMacroExpander.expandReadEofSignal`, applied by
  `Jvm/WasmExprCompiler` -- the built-in is called with the backend's `(nil nil)` eof parameters and
  the expansion tests the nil result. Sound for exactly these three operators because a successful
  read never answers nil; runtime helpers keep their old throw as a BACKSTOP. Returns null for a
  literally nil `eof-error-p` or an omitted one on `read-line`.
- **Two gates easy to miss**: `mayCreateInstances` scans the SOURCE program, so the read family is in
  `constructsInstance`; and `#'read-char`/`#'peek-char`/`#'read-byte` joined
  `BuiltinFunctionWrappers.REFERENCE_GATED_FUNCTIONS`, their wrappers now constructing a condition.
- **The `--component` socket rewrite needs the same lowering under its ALIAS**: `WasmSocketsRewrite`
  maps `(read-char s)` to `(%io-read-char s)` falling through to `rontolisp::%read-char-raw`, so
  lowering only the public name left every sockets.lisp-splicing component with the OLD uncatchable
  trap at EOF. `WasmExprCompiler` lowers `%read-char-raw`/`%read-byte-raw`/`%read-line-raw` too.
- **`read` is deliberately NOT in this family**, nor default `read-line`: both answer nil at end of
  input and `read`'s datum may legitimately BE nil.

## A condition's `:report` is what PRINTS it
**Invariant: the text a condition REPORTS has exactly one implementation, `%condition-report-str`,
and both the printer and the signal message go through it.** `princ`/`princ-to-string`/`format ~A`
answer the report; `prin1`/`~S` keep the `#<TYPE :SLOT value ...>` syntax -- CLHS's escape-mode
split, matching SBCL.

- **It rides the `print-object` seam** ([clos.md](clos.md)): `expandPrintObjectHook` fires when the
  program defines a `print-object` method **or** can build a condition, and the escape-off arm of
  `%print-object-str` becomes `(if (%obj-p x) (or (%condition-report-str x) (%princ-to-string x))
  (%princ-to-string x))`. A `print-object` method on a condition class still wins in BOTH modes.
- **`%condition-report-str` answers nil when the class reports nothing** and every caller supplies
  its own fallback, so an under-approximating gate degrades to pre-report text instead of failing.
- **The class partition** (`conditionReportGroups`): a class's report is its own `:report`, else the
  nearest ancestor's along the SLOT-LAYOUT parent chain, else -- when it carries
  `format-control`/`format-arguments` -- CLHS's `simple-condition` report. Groups are keyed by report
  owner / slot-index pair, NOT one clause per class: cl-postgres registers 100+ condition classes and
  a per-class dispatch is the same 90 KB-in-one-method trap
  ([jvm-method-size-limits.md](jvm-method-size-limits.md)).
- **`%format-condition` renders through `%fmt-render`** ([format.md](format.md)). A control that is a
  FUNCTION is called on the stream through FIXED-ARITY `funcall`s for 0-3 arguments: `apply` would
  drag the whole wasm eval runtime into every program that prints a condition.
- **The gate is `mayCreateConditions(program, registry)`**, the CONDITION half of
  `mayCreateInstances` sharing its `constructsInstance` case split (plus
  `#'error`/`#'warn`/`#'cerror`, and `%obj-new` restricted to `%class-` tags descending from
  `condition`). Answered TWICE in `expandTopLevelDefinitions` -- on the source program (whose only
  condition may be the `simple-error` a `handler-case` synthesizes) and on the expanded program.
  Recorded in `ClosRegistry.routesConditionReports()` rather than a `Ctx` flag, so it needs no
  `WasmAsyncEmit.freshCtx` line.
- **Trap: the generated defuns must not contain the SYMBOL `with-output-to-string`.** The wasm-GC EH
  gate (`programUsesEhForm`) scans for it and runs AFTER these defuns are spliced in, so leaving the
  macro there forced EH mode on every program that merely signals a typed condition;
  `renderedToString` pre-expands it.
- The interpreter loads the same generated AST (`ensureConditionReportRuntimeLoaded`) and RE-loads
  whenever the registry it partitions changed (a stamp over class and report counts).
- **Lite**: the rewrite is per CALL FORM, so a condition reached through a FUNCTION VALUE gets the
  raw conversion, exactly as a `print-object` method does. **Known deviation**: the
  string-designator path renders EAGERLY, so printing renders the message a SECOND time -- visible
  only when it still contains a live directive (`(error "~a" "~a")` prints `NIL`).

## The condition floor is narrowed to what the program can construct
The compile path shrinks the condition runtime along three axes; the interpreter never narrows, and
every narrowing is IMPOSSIBILITY-based.

- **`conditionNarrowing`** scans the expanded program for the constructible `%class-` tag set plus
  whether any site can hand `%format-condition` an UNRENDERED control. Tag sources: literal datums of
  the signal family, literal-tag `%obj-new`, plus always the synthesized simple-* three. BAILS to
  `none()` on a computed datum, `eval`/`symbol-function`/`fdefinition`, an escaping `#'error`-family
  value or quoted designator in data, `--dynamic`, restart mode. Name forgery from computed strings
  can reach a pruned arm -- the failure is the caller's fallback report text, never a lost signal.
- **`%format-condition` declines the renderer** when every possible control is a directive-free
  literal (or nil) with nil arguments -- the common case, since every string-datum signal site
  pre-renders (`formatMessagePieces`). Only an explicit `:format-control` initarg (surface keyword
  check plus the baked `:initform`/`:default-initargs` cons check inside generated constructors'
  `%obj-new`) forces it back. On zlib that is **-61 KB**. **Trigger: the double-render deviation
  requires the renderer, so a declined artifact prints a rendered-once message; if that bites,
  render once EVERYWHERE, not by un-declining.**
- **`WasmInstanceLayouts.emit` takes a used-tag set** (`usedLayoutTags`): a `%class-`/`%struct-`
  layout ships only when its tag or bare name occurs as a symbol in the final program (plus the
  simple-* three the handler lowering synthesizes during Pass 2), with null (= bake all) under
  `--dynamic`, an embedded eval runtime, restart mode, subclass enumeration,
  `find-class`/`change-class`/`allocate-instance`/`symbol-function`/`fdefinition`. The JVM already
  interned per referenced tag (`LayoutPool`).
- **`needsRuntimeErrorDispatch` no longer misreads handler clauses**: `(handler-case b (error (e)
  ...))` used to parse as `(error <computed> ...)` and bake the whole per-class construction runtime
  into EVERY handler-case artifact. With clause heads skipped, the 89,138 B probe is **23,341 B**.

## The routing gate asks whether a condition can be NAMED
`mayCreateConditions` cannot part company with `mayCreateInstances` by proving the body will not
signal -- the handler prologue synthesizes a `simple-error` for a caught RAW trap however the body
fails. What decides the gate is whether program code can ever HOLD that instance.

- **`handler-case` counts only when some clause binds a variable its own body mentions**
  (`handlerCaseBindsCondition`); otherwise the instance never leaves the landing pad. The occurrence
  test is deliberately blunt.
- **`ignore-errors` counts only where a SECOND value can be read** (`receivesMultipleValues`, a
  whole-program answer): any occurrence of
  `multiple-value-bind`/`-list`/`-call`/`-setq`/`-prog1`/`nth-value`/`%mv-spill` turns it back on.
- **A clause HEAD is not a call, and that skip is shared.** `evaluatedClauseForms` is the one helper
  answering "which sub-forms of this clause-bearing operator are EVALUATED", used by this scan and by
  `needsRuntimeErrorDispatch`. **A new scan that walks a program as code goes through it** -- the
  same misread has cost three times (a tagbody-tag `CONTINUE`, the dispatch runtime, this).
- At `--optimize=size`: `(print (handler-case (+ 1 2) (error () 0)))` 23,216 -> 5,713 B,
  `(print (ignore-errors (+ 1 2)))` 22,782 -> 5,638 B. Gating the renderer's defun on a pre-Pass-2
  scan for printing operators is NOT sound: `format`'s `~A` lowers to `%princ-piece` after it.

## Signal messages are lazy on wasm-GC
**Invariant: on the wasm-GC backends a signal's message string is rendered only where a condition
value can reach program hands -- never at the signal point.** In EH mode the entry landing pad is a
second payload reader, so both gates go broad; outside EH mode nothing is observable.

- **`WasmErrorCompiler.compileCond` / `WasmSignalCondCompiler` never compile their message operand**
  (payload cdr = nil), unconditionally: those forms always carry a real instance in the car. The
  instance operand still compiles (initargs are evaluated at construction per CL).
- **A plain `%error`'s message operand compiles only when `Ctx.condMessagesObservable`** -- its
  message IS what a caught raw trap becomes a `simple-error` from AND the only text the entry landing
  pad has. Forced on under restart mode / `--dynamic` / EH mode; copied in `WasmAsyncEmit.freshCtx`.
- **The routing gate narrows outside EH MODE**: `expandTopLevelDefinitions` takes
  `lazyConditionMessages` (`WasmLispCompiler` passes `!reportsUncaught`), under which the answer is
  `mayHoldConditions` = `mayCreateConditions` minus the throw-only constructions (literal-typed
  `error`/`cerror`, `signal`, the read family's EOF lowering). The keyword constructor every
  `define-condition` splices is exempted by SHAPE (`conditionConstructorName`) unless another form
  references it -- without that every library that merely DEFINES conditions (chipz) kept the whole
  renderer.
- **`reportsUncaught` is a PRE-SCAN, not the definitive `ehMode`**: it runs before the passes that
  finish deciding EH mode, so `WasmLispCompiler` scans for triggers that can accompany a signal
  (`programUsesEhForm`, `catch`/`throw`, restart mode, async mode). The one it cannot see is a
  cross-lambda `return-from`, lowered afterwards by `CrossLambdaExitLowering` (which must run after
  the expansion or a GENERATED dispatcher's `return-from` would go unlowered), so a program whose
  SOLE EH trigger is that keeps the narrow gate and its landing pad prints an empty report.
  **Re-evaluate if** the cross-lambda lowering ever becomes safe to run first.
- **`%no-applicable-method` signals VALUES, not prose**: `(error 'no-applicable-method-error
  :%nam-operation tail :%nam-datum-class (%class-designator arg))` against a class seeded ON DEMAND
  (`ClosRegistry.ensureNoApplicableErrorSeeded`; slot names %-fenced so `registerSlotPosition` cannot
  make a user slot ambiguous). Injection moved BEFORE the report-renderer injection (its tag must be
  in `conditionNarrowing`'s set) and AFTER the routing answer (a dispatcher alone must not flip
  routing on). Deliberate narrowing: a `(simple-error ...)` clause no longer matches it.
- zlib `--optimize=size`: P1 127,026 -> 117,118 (-7.8%), component 131,677 -> 121,723, output
  byte-identical. **The remaining floor is a floor**: zlib still carries the value printers (~4.6 KB)
  because the entry edge is `%SEQ-TO-STRING`, reachable through `%FILL-RUNTIME`/`%REPLACE-RUNTIME`,
  whose element conversion IS princ semantics. **Trigger: a program family carrying the printers ONLY
  through `%seq-to-string`/`%schar-set-runtime` and where those bytes matter.**

## An uncaught condition reports ONE line, the same one, on all four backends
**Invariant: a signaled condition escaping the top level writes exactly
`Unhandled condition: <report>` to standard error, then the process exits the way it always did.**
Built from `compiler/UncaughtReport.PREFIX` at all three emission sites; the report text is the one
`princ` writes.

- **Interpreter / compile failures** (`RontoLispCli.runReporting`): only `main` catches -- `run`
  still throws, so an embedded caller keeps the exception with its type and cause. A rontolisp
  diagnostic (read error, compile failure, bad command line) says `error:` instead and keeps the
  `file:line:column:` prefix (`locateCompileFailure`). **A RUNTIME condition carries no such prefix
  on any backend, deliberately**: the position table records on the COMPILE path only, because
  recording it in the interpreter would put a `file:line:` on runtime error text that `ci-spec.yaml`
  and the doc examples pin byte for byte (`SourceProvenance`, whose javadoc holds the trigger).
  `RONTOLISP_DEBUG` additionally prints the JVM trace.
- **JVM** (`JvmUncaughtHandler`): a last exception-table entry over the whole of `main` catching
  `RuntimeException`; prints the line, EMPTIES the stack trace and RETHROWS. **Not
  `System.exit(1)`**: a compiled class's `main` is invoked in-process by ~110 assertions here and by
  any embedder; the launcher supplies exit 1.
- **wasm-GC, EH MODE ONLY** (`WasmUncaughtReportCompiler`): the entry function wraps its body in
  `block $trap` + `block $cond (result (ref null eq))` +
  `try_table (catch $lisp-cond 0) (catch_all 1)`; the landing takes the payload as the inner block's
  result, splits it into `__uc_cond$N`/`__uc_msg$N` and compiles
  `(%warn (%string-concat "Unhandled condition: " (if cond (%condition-report-str cond) msg)))`, each
  half guarded by `(let ((v ...)) (if v v ""))` so a nil never renders as `NIL`; `%warn` is the
  existing fd-2 writer, exempt from the lazy-message narrowing. Then `unreachable`: the exit CLASS
  every host and test expects is the trap. The try_table's own `end` restores a reachable, empty
  stack while `block $cond` owes an `eqref`, so an `unreachable` sits between them. **Export wrappers
  keep the catch_all-only landing** -- a host call's failure is the host's to report.
- **It is a FIFTH producer of the reserved `*error-output*` handle**, one the compiler SYNTHESIZES in
  Pass 2 and invisible to any scan of user source, so `--component --optimize` pruned
  `wasi:cli/stderr` out from under it. `WasmUncaughtReportCompiler.emittedFor(ehMode)` is now the ONE
  predicate: `WasmLispCompiler` gates the pad on it and ORs the same value into
  `WasmComponentBuilder.Narrowing`'s `reachesStandardError`
  ([standard-output-redirect.md](standard-output-redirect.md)).
- **Outside EH mode nothing changes, byte for byte**; reporting there means turning EH mode on for
  every program (121,572 -> 175,486 B on the two-line toy). `--no-gc` is exempt outright; a
  `--no-wasi` reactor compiles the pad but writes into the discarding `fd_write` sink, which is why
  `doc/{en,ja}/guides/wasm-gc-module.md` still says a load-time failure there is a bare
  `RuntimeError: unreachable`. Worst-case cost on zlib `--optimize=size`: 72,837 B ->
  `condMessagesObservable` 76,812 -> broad routing gate 85,391 (+17.2%), taken deliberately since
  the first step alone printed `Unhandled condition: ` and nothing else. **Re-evaluate if** the
  report renderer becomes narrowable to the classes that can actually ESCAPE.
- Pinned cross-backend by `ci-spec.yaml`'s `standalone:` list -- a section `CiSpecE2eTest` runs one
  program at a time, per backend; the corpus cannot host these since running one ends the program.

## cerror, signal-operator function values, runtime type dispatch
- `cerror` has TWO lowerings on the restart-mode gate: outside it `expandCerror(cons, registry)`
  drops the control and emits `(error datum args...)` (behavior-identical, since nothing could invoke
  a `continue` restart); in restart mode `expandCerror(cons, registry, true)` emits the REAL
  `(restart-case (error ...) (continue () :report continue-format nil))`.
- `error`/`signal`/`warn`/`cerror` also have FUNCTION values. Interpreter: `registerEval` defines
  real functions that rebuild the literal call from evaluated arguments (`rebuildSignalForm`) and
  re-enter `eval`, `resolveFunction` checking the function namespace BEFORE the
  macro/special-operator guard; a NON-literal `error` datum evaluating to a SYMBOL re-dispatches as a
  condition-type designator (`expandError`'s `runtimeTypeDispatch`). Compiled:
  `BuiltinFunctionWrappers.SIGNAL_FUNCTIONS` wrappers injected ONLY under a literal `(function op)`
  reference (`referencesFunctionValue`), forwarding the datum only.
- **A NON-literal `(error TYPE args...)` datum WITH initargs dispatches on the COMPILED backends
  too** (jzon's `%raise`, cl-postgres' `(error (get-error-type code) :code ...)`). NOT inlined at the
  call site -- at 165 registered classes the per-class expansion reached 90 KB in one method, past
  the JVM's 64 KB limit. The site lowers to `(%error-runtime datum (list args...))` and
  `expandTopLevelDefinitions` injects one construction helper defun per registered CONDITION class
  (`%ERROR-RT-n`, the same `expandTypedSignal` a literal call gets, over `getf` reads with each
  slot's `:initform` as the default) plus the `%error-runtime` dispatch defun matching against both
  the qualified and (when unambiguous) plain spelling.
- **The dispatch is CHAINED** (`%error-runtime` -> `%ER-1` -> ..., the `chainedDispatchDefuns` shape,
  ~600 cons nodes per segment): one `cond` lowers to nested `if`s on the JVM, so past ~140 condition
  classes the outermost arm's else-branch overflowed the signed-16-bit branch encoding. One shared
  shape on all four backends.
- A NON-condition class name and any non-symbol fall to the `expandObjectSignal` arm; the
  interpreter's inline dispatch still constructs ANY class. A DATUM-ONLY non-literal call keeps the
  object-designator path (constructing a slot-less instance would run its `:report` over nil slots).

## Phase 4 -- handler-bind + the restart stack
**Invariant: the restart system is ONE shared Lisp-level lowering in `LispMacroExpander`, identical
on the interpreter, the JVM and both wasm-GC backends.** No backend has a per-form compiler class; a
divergence can only come from the primitives underneath (`catch`/`throw`, `unwind-protect`, globals,
closures), all pinned cross-backend. `--no-gc` keeps the lite lowering.

- **Two dynamic stacks, both TOP-LEVEL GLOBALS** (`%HANDLER-CLUSTERS%`, `%RESTART-CLUSTERS%`,
  injected as `defvar`s; plus `%HANDLERS-RAN%`, the completed-walk mark), mutated with plain `setq`
  and restored through an `unwind-protect` cleanup over a LEXICALLY saved value. **Deliberately NOT
  special-`let` rebindings**: the compile paths skip the special-binding restore on the error-throw,
  `catch`/`throw` and cross-lambda `return-from` channels
  ([dynamic-special-variables.md](dynamic-special-variables.md)) while `unwind-protect` cleanups run
  on EVERY channel on EVERY backend, so a special binding would leak a handler cluster on exactly the
  path the feature exists for. **If you ever move these to `let`, the restore holes come back.**
- **The restart transfer rides `catch`/`throw`** with a FRESH cons as the tag (`(list '%restart)`),
  so tag identity is `eq` and cannot collide with a user tag -- which buys crossing function
  boundaries, running intervening cleanups, and passing through `handler-case` regions uncaught.
- **Clause bodies are compiled INLINE in the dispatch, never wrapped in a lambda** -- what makes
  postmodern's `transaction.lisp` shape work: the clause body `(go start)` targets a tagbody of the
  SAME function and stays a plain goto/br. A lambda wrapper would push every retry clause onto the
  cross-lambda `go` lowering.
- A restart record is the list `(%restart name invoker report interactive test)`; the invoker takes
  the argument LIST and is called with ONE fixed-arity `funcall` -- `apply` would drag the WASM eval
  runtime into every restart program.
- `handler-bind` pushes one cluster of `(type-test-closure . handler)` entries. **`%run-handlers`
  walks the clusters and, per CLHS, rebinds the global to the REMAINING clusters while a cluster
  runs**, so a handler that itself signals does not re-enter its own cluster; a handler that returns
  declines and the walk continues.
- **The signal hook.** `expandError`/`expandWarn`/`expandSignalMacro` take a `signalHook` boolean;
  when set they insert `(%run-handlers <instance>)` BEFORE the `%error-cond`/`%signal-cond`/`%warn`
  terminal, so handlers run at the signal point with the signaling frame's restarts still established
  (restart-mode `warn` is wrapped in a `muffle-warning` `restart-case`). **Every restart-mode signal
  terminal CARRIES the instance the hook just ran**: the string-designator error arm throws
  `%error-cond` instead of `%error`, and `expandObjectSignal`'s string/symbol arms bind their fresh
  instance (`__signal_inst`) and hand it to both `%run-handlers` and the terminal -- the identity
  contract the `%hb-guard` mark depends on.

### Errors BUILT-INS raise run handler-bind handlers too
Rove's failure-recording model is `handler-bind` around USER code, so `(car 1)`, an out-of-range
`aref`, `(/ 1 0)` and an undefined function must reach the handlers.

- The `handler-bind` expansion wraps its body in the internal `(%hb-guard body)` landing pad,
  compiled per backend (`JvmHandlerCaseCompiler.compileGuard`,
  `WasmHandlerCaseCompiler.compileGuard`, `LispEvaluator.evalHbGuard`): a region that synthesizes the
  `simple-error` of a condition-less throw, runs `%run-handlers` -- the FULL cluster stack from the
  innermost, CLHS rebinding included, so ONE pad run covers every enclosing cluster and outer pads
  skip by the mark -- and rethrows CARRYING the instance. The pad never touches the hc-depth channel,
  has no cleanup (no `UnwindScope`, no trampoline), and does not catch the block-exit tag.
- **Identity contract**: `%run-handlers` sets `%handlers-ran%` to its argument AT THE END of a
  completed walk, so a pad recognizes an already-walked condition by `eq` and handlers run ONCE.
  End-of-walk (not entry) marking keeps a nested signal inside a handler from clearing the outer
  condition's mark.
- **The interpreter ADDITIONALLY runs handlers at the SIGNAL POINT for built-ins**:
  `LispEvaluator.apply` wraps `builtIn.body().apply` and, on an escaping `LispEvalException` -- or a
  raw `IndexOutOfBounds`/`NegativeArraySize`/`Arithmetic`/`ClassCast` wrapped into one first
  (`builtinFailureMessage` names the built-in when the raw message is letterless) -- reuses or
  synthesizes the condition and runs the walk BEFORE unwinding, so restarts established below the
  handler-bind are still invocable. Zero cost until an exception escapes.
- **Deviations**: on the COMPILED backends a raw error's handlers run at the `handler-bind`
  boundary -- intervening cleanups have run and restarts below it are gone (CL runs handlers first);
  a SIGNALED condition keeps exact signal-point semantics everywhere. wasm-GC runs handlers only for
  `$lisp-cond` throws, so **a rove test whose body traps still ends a wasm run**.

### A `handler-case` joins the cluster stack, so it SHADOWS an enclosing `handler-bind`
**Invariant: CLHS 9.1.4.1 -- handlers run MOST RECENT FIRST and `handler-case` transfers control, so
a `handler-case` established inside a `handler-bind`'s extent handles the condition and the enclosing
handler-bind handler never runs.**

- `LispMacroExpander.handlerCaseProtectedForm` wraps the PROTECTED FORM (only it) in the same
  `let`-saved / `unwind-protect`-restored push `handler-bind` uses, pushing one cluster of
  `(type-test-closure . nil)` entries. **The nil cdr is the marker**: a nil handler is a handler-case
  clause, which HANDLES by transferring control, so `%run-handlers` stops its walk there
  (`__rh_stop`). The transfer is the ordinary throw the signal terminal performs immediately after,
  so no backend needed a new control path.
- **Wrapping only the protected form is what pops the cluster for the clause bodies**, so a clause
  body that signals is not caught by its own handler-case and reaches the enclosing handler-bind.
- **Three call sites = the three handler-case implementations** (`LispEvaluator.evalHandlerCase`,
  `JvmHandlerCaseCompiler.compile`, `WasmHandlerCaseCompiler.compile`), each passing its own
  restart-mode flag (`restartRuntimeLoaded` / `ctx.restartMode`). **Restart mode is the gate**: with
  no `handler-bind` anywhere there is no cluster stack to shadow, so every other program is
  byte-identical. `ignore-errors` inherits it.

### The restart-mode gate
**`LispMacroExpander.usesRestartSystem(program)`, computed on the SURFACE program** (the four macros
plus a call to / `#'` reference of a restart-runtime function). The scan matches those names in
OPERATOR POSITION of evaluated forms only: it recurses into sub-forms, never into spine cells, skips
`quote`d data, ignores keyword heads. **The old spine-walking scan read ANY occurrence as an
operator, so chipz's bzip2 decoder, whose `tagbody` has a tag named `CONTINUE`, put every program
that loads chipz into restart mode** (~7 KB on the zlib row). A binding pair or clause head spelling
a restart name still over-approximates to true (the safe direction).

It must be computed before `expandTopLevelDefinitions` (which re-runs it to inject the runtime defuns
and the two globals) and threaded into: the JVM `blockExitChannel` / WASM `blockExitTag` (the
expansions ride `catch`/`throw`, and on WASM that also implies EH mode), `mayUseInstances` (the hook
constructs `simple-*` instances), and `Ctx.restartMode`. **All of these pre-scans run before Pass 2,
where the expansions happen, so none of them can see the expansion products -- that is why the gate
is a separate surface scan.**

- **Trap**: the WASM chunked top level clones `Ctx` through `WasmAsyncEmit.freshCtx`, which
  enumerates flags EXPLICITLY. Without `restartMode` there, top-level chunks compiled the signal hook
  OFF while defun bodies had it ON, so a `handler-bind` at top level silently never ran its handlers.
  **Any future `Ctx` flag needs the same line.**
- Interpreter: no injection pass, so `ensureRestartRuntimeLoaded()` evaluates the same generated AST
  on the first restart-system form or the first resolution of a restart-runtime name. The flag
  doubles as the signal-hook gate; the interpreter re-expands per evaluation so later signals pick
  the hook up.
- **`FreeVarAnalyzer` learned all four macros** (expand-before-walking); `handler-bind` uses
  `expandHandlerBindForAnalysis`, substituting `t` for the clause type tests so an unknown or
  compound type spec cannot reject the analysis.
- Lite deviations (on the doc pages): `&optional` clause parameters take nil rather than their
  default, no condition-restart association, restart records print as plain lists,
  `:report`/`:interactive` stored but never rendered/run (no debugger),
  `check-type`/`assert`/`ccase`/`ctypecase` offer no `store-value` restart.
- `use-value`/`store-value` share `LispMacroExpander.valueRestartDefun`: invoke the innermost restart
  of that name with ONE value, nil when none is active. In `RESTART_RUNTIME_FUNCTION_NAMES` and
  `PackageRegistry.CL_FUNCTIONS`.
- **ci-spec `restart-system` puts the whole concatenated program into restart mode**, so a hook
  regression shows up as an unrelated case failing.

### `signal` declines a handler-case no clause matches -- on every backend
**Invariant: CLHS 9.1.4.1 -- `signal` transfers control only to a handler that will handle the
condition. A `handler-case` whose clause types do not match is not applicable: the signal passes it
by, returns nil so the forms after it run, and the handler-case stays armed for a later matching
condition -- identically on all four backends.** The compiled backends used to approximate with the
handler-DEPTH counter alone and turned an unmatched decline into a top-level abort.

- The clause types ride the same cluster stack, now outside restart mode too:
  `handlerCaseProtectedForm` is called by both compiled emitters with
  `ctx.restartMode || ctx.signalClauseMatch`.
- **`%signal-cond` consults the stack**: `Jvm/WasmSignalCondCompiler` still require the depth channel
  to be positive FIRST (depth is per thread of control while the cluster stack is a shared global),
  then call the injected `%hc-match-p` defun -- an iterative walk over `%handler-clusters%` testing
  nil-cdr entries only, because a handler-bind entry never transfers at `%signal-cond` -- and throw
  only on a match. A handler-case with only a `:no-error` clause pushes nothing and is therefore
  declined, which is also CL.
- **The gate is `LispMacroExpander.needsSignalClauseMatch(program)`**: the program contains BOTH a
  `signal` (operator position, or `#'signal`) AND a `handler-case`/`ignore-errors` head -- a surface
  scan with the operator-position discipline, computed by each compiler before
  `expandTopLevelDefinitions` (which re-runs it to inject `%hc-match-p`, prepend the
  `%handler-clusters%` defvar outside restart mode, and disable the no-definitions fast path). A
  program missing either half keeps the historical emission byte for byte; the interpreter is
  untouched. `WasmAsyncEmit.freshCtx` copies the flag.
- Known blind corner: a `signal` or catching form reachable only through a channel the surface scan
  cannot see (runtime `eval`, a computed designator forged from quoted data) keeps depth-only
  behavior.

## A built-in error carries its CONDITION CLASS
`(handler-case (car 1) (type-error ...))` matches, and so does rove's `(ok (signals (car 1)
'type-error))`. **The class is decided where the failure is DETECTED, never by pattern-matching the
message at the catching end** -- except for the failures the backends report as bare text.

- **Interpreter**: `LispEvalException.ofClass(className, message)`;
  `LispEvaluator.synthesizeCondition` (the ONE synthesis point, shared by `handler-case` and
  `%hb-guard`) builds it through `ClosRegistry.newReportingCondition`, which nil-fills the layout and
  puts the message in `format-control`. A raw Java failure escaping a built-in is classified by
  `LispEvaluator.rawFailureConditionClass` at the `apply` seam.
- **JVM**: the landing pad (`JvmHandlerCaseCompiler.emitSynthesizeCondition`) classifies the caught
  `Throwable`: `ClassCastException`/`IndexOutOfBoundsException` -> `type-error`;
  `ArithmeticException` -> `division-by-zero` when its message contains
  `ClosRegistry.DIVISION_BY_ZERO_MESSAGE_TOKEN` else `arithmetic-error`; and -- the message
  exceptions -- text starting `The variable `/`The function ` and ending ` is unbound`/` is
  undefined` -> its cell-error class, and text starting
  `ClosRegistry.EXPECTED_INTEGER|NUMBER_MESSAGE_PREFIX` (thrown by `_big`/`_dbl`) -> `type-error`,
  because those sites are plain `RuntimeException`s emitted in bytecode with no channel to carry a
  class. The arms are compiled Lisp forms built by `LispMacroExpander.reportingConditionForm`, so no
  slot index is baked here.
- **WASM**: the pad is unchanged, and correctly so -- only `$lisp-cond` throws land in it. **Two
  families diverge by CLASS rather than catchability**: an undefined-function call and a non-number
  reaching arithmetic are both catchable but as a `simple-error`. **The stub cannot construct the
  typed instance**: it is produced during BODY compilation, after `mayCreateInstances` fixed whether
  the artifact has an instance representation and after `usedLayoutTags` chose which layouts to bake
  (`%OBJ-NEW reached the compiler with no instance representation`). **Trigger: teach both gates
  about undefined calls** -- `usedLayoutTags` already stands in for the `end-of-file` tag on the read
  family's presence -- and fix the two families together.
- **Undefined functions keep the call-time stub contract**: a call to a name with no definition
  compiles to `The function X is undefined` at call time plus a compile-time warning, matching the
  interpreter's late binding. It stays a STRING signal for the gate reason above.
- **The message a raw host failure reports is rontolisp's, not the host's**:
  `ClosRegistry.TYPE_ERROR_MESSAGE` replaces a `ClassCastException`'s Java class names and
  `INDEX_OUT_OF_BOUNDS_MESSAGE` the JVM's `Index 10 out of bounds for length 3` (whose length counts
  the layout cell in slot 0). Per-site texts a built-in writes itself are kept and are NOT identical
  across backends. **Trigger: if a program needs the operator name in a compiled type error, fix it
  by per-operator emission at the check, not more message parsing at the pad.** The substitution does
  NOT reach the UNCAUGHT top-level line on the JVM -- deliberate.
- **Restart mode moves the undefined-function text out of the pad's reach**: the string-datum `error`
  arm builds its `simple-error` at the SIGNAL point and hands it over on the condition channel, so
  `(handler-case (nosuchfn) (undefined-function ...))` matches on the JVM in a plain program and not
  in a restart-mode one. **Trigger: restart mode is where both instance gates are already open, so
  the stub CAN carry its class there.**
- **`conditionNarrowing` marks the five classes constructible**
  (`LispMacroExpander.rawFailureConditionClasses`): no site names them, but a pad can build one, and
  without the mark a caught `(car 1)` would print as a bare `#<TYPE-ERROR>`. Unlike the simple-*
  three it is CONDITIONAL on the program establishing a pad at all (`LANDING_PAD_HEADS` ->
  `ConditionTagScan.hasLandingPad`); marking them unconditionally cost zlib 680 B.

## A non-number reaching arithmetic signals a catchable type-error
**Invariant: a non-number operand reaching an arithmetic or comparison operator
(`+ - * / mod rem = < > <= >= min max abs gcd`, the bitwise family, `1+`/`1-`, and every float
coercion behind `sqrt`/`exp`/...) signals a CATCHABLE error carrying the interpreter's exact text --
`Expected integer, got: <prin1>` on the exact path, `Expected number, got: <prin1>` on the float path
-- byte-identical on all four backends.** Prefixes in
`ClosRegistry.EXPECTED_INTEGER|NUMBER_MESSAGE_PREFIX`; detected at each backend's coercion FUNNEL,
never by wrapping operator sites. Which prefix a case sees depends on which dispatch arm the OTHER
operands select -- `(+ 1 nil)` exact, `(+ 1.5 nil)` float.

- Funnels: `Environment.asLong`/`asDouble`/`asBigInteger`; JVM `_big` and `_dbl`
  (`JvmNumericRuntimeBuilder`), which test-and-throw `prefix + _lispToString(x)` where a bare
  `checkcast` used to let null through to a later NPE (`_abs`'s BigInteger arm routes through `_big`
  for the same reason) -- one `instanceof` on each SLOW arm only, fast arms byte-identical, class
  +1.4 KB; wasm `_int_val` -> `_type_err_int` and `_as_f64` -> `_type_err_num`
  (`FUNC_TYPE_ERR_INT`/`FUNC_TYPE_ERR_NUM`, bodies `WasmEmitHelper.buildTypeErrBody`, signature
  `TYPE_PRINT_VAL` so no new type entry).
- **Outside EH mode both wasm bodies are a bare `unreachable`** -- the failure stays a messageless
  trap. **The message prefixes are interned EARLY (`WasmLispCompiler`, beside `tSymEntry`): a string
  added during code emission lands after the data segment content is fixed and reads back as
  blanks.** `_int_val`'s limb-tier arm still TRAPS explicitly ([wasm-bignum.md](wasm-bignum.md)'s
  exact-or-trap boundary is about values that ARE integers). The `_as_f64` ladder was reordered
  float-first in the same change, turning the check's +9.8% float cost into a -21% win
  ([wasm-shared-coercion.md](wasm-shared-coercion.md)). Size: +33-37 B per non-EH module, +283 B per
  EH module. `--no-gc` unaffected, still traps.
- **What still traps on wasm-GC**: anything not funneled through `_int_val`/`_as_f64` -- `(car 5)`,
  division by zero, kinded/generic aref casts, the limb-tier boundaries.
- **The funnels' reach is wider than arithmetic**: a STORE into a packed float array goes through the
  same `_dbl`/`_as_f64`, so `(setf (aref #d(1.0 2.0 3.0) 0) "x")` moved with them (the JVM used to
  leak a raw `ClassCastException`). Pinned by `JvmFloatArrayTest`'s
  `nonRealStoreIsATypeError`/`singleNonRealStoreIsATypeError` -- the only two tests in the repo that
  noticed, and the reason to run the WHOLE suite after changing a shared runtime helper.
- **Class divergence, deliberate**: interpreter and JVM signal `type-error`, the wasm pair
  `simple-error`. Pre-existing edge unchanged: a condition thrown from INSIDE a wasm to-string
  capture leaves the capture flag set.

## Out of scope (still)
The interactive debugger (`break`, `*debugger-hook*`, rendering a restart's `:report` or running its
`:interactive` function), condition-restart association, a `store-value` restart for
`check-type`/`assert`/`ccase`/`ctypecase` (`expandCcase` -> `expandEcase`, `expandCtypecase` ->
`expandEtypecase`), `--no-gc` catching (the GC path's `$lisp-cond` tag has no MVP equivalent, which
is why `--no-gc` rejects the forms outright rather than degrading), and the
special-`let`-restore-on-return compile-path limit. Postmodern is the real restart customer
(`prepare.lisp:54-66`, `transaction.lisp:63-70`); verbatim cl-postgres needs no restart system at
all, so **`restart-case` alone unblocks nothing real**.

## Tests
- Behavior in `LispEvaluatorTest` / `JvmLispCompilerTest` (`compileAndRun*` twins) / the `eh*` block
  of `WasmLispCompilerIntegrationTest`; `--no-gc` compile-error pins in `NoGcWasmCompilerTest`. Named
  groups: `conditionReport*`, `simpleConditionFamily*`, `warnRenders*`, `aRuntimeControlStringDatum*`,
  `aPrintObjectMethodStillWins*`, `aConditionWithNoReport*`,
  `readCharEndOfFileIsCatchableAsEndOfFile`, `noApplicableMethodIsCatchableAndReportsTheSameText`,
  `handlerBindSees*`, `handlerBindRunsEachClusterOnceForABuiltInErrorInnermostFirst`,
  `arefOutOfBoundsAndNegativeMakeArrayAreCatchable`,
  `anInnerHandlerCaseShadowsAnEnclosingHandlerBind` (+3),
  `signalFallsThroughAHandlerCaseWhoseClausesDoNotMatch` (+3),
  `nonNumberArithmeticOperandsSignalCatchableTypeErrors`, the restart block (15-16 cases each),
  `compileRuntimeErrorDispatchScalesPastTheBranchLimit`, `anUncaughtCondition*`, `ehUncaught*`, and
  the `compileAndRunHandlerCaseIn*` block -- which must COMPILE, LOAD and RUN the class, since the
  broken class was written without complaint and only failed at link time.
- Gates in `LispMacroExpanderTest`: `conditionNarrowing*`,
  `anExplicitFormatControlInitargForcesTheRenderer`, `aComputedDatumMakesTheConditionSetUnknowable`,
  `aDirectiveFreeLiteralFormatControlStillDeclinesTheRenderer`,
  `aHandlerCaseThatNeverNamesItsConditionDoesNotRouteReports`,
  `ignoreErrorsRoutesReportsOnlyWhereASecondValueCanBeRead`,
  `a{ThrowOnlyConstructionDoesNot,HeldConditionStill}RouteReportsWhereMessagesAreLazy`,
  `aNonOperatorRestartNameDoesNotFlipRestartMode`,
  `anOperatorPositionRestartFormStillFlipsRestartMode`,
  `needsSignalClauseMatchRequiresBothASignalAndACatchingForm`; plus
  `WasmLispCompilerTest.typedErrorWithLambdaReportCompilesOutsideEhMode`,
  `ByteCodeWriterTest.generateAndRun{TypedCatch,CatchAny}Handler`,
  `WasmTreeShakerTest.shakesEhModeModules`, `RontoLispCliTest`, `JvmFloatArrayTest`.
- ci-spec: `condition-objects`, `condition-types`, `condition-report-printing`,
  `signal-runtime-control-string`, `handler-case-catches-typed-and-plain-errors` (+2),
  `handler-case-in-argument-position`, `restart-system`,
  `signal-declines-an-unmatched-handler-case`, `no-applicable-method-report`,
  `non-number-arithmetic-operands-are-catchable`, `runtime-type-dispatch-residue`,
  `runtime-type-dispatch-and-symbol-designators`, `postmodern-language-incidentals`, plus the
  `standalone:` list. Their presence puts the concatenated program in EH mode, so
  `CiSpecE2eTest.runBackend` passes `-W exceptions=y` to both wasmtime invocations.
  `ParseNumberE2eTest` and `JzonE2eTest` cover the report text and the runtime dispatch.
