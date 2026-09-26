# Error handling: unwind-protect + condition objects + handler-case + restarts

**Backend contract: interpreter, JVM and both wasm-GC backends (Preview 1 + `--component`, incl.
serve) are full; only `--no-gc` rejects `unwind-protect` / `handler-case` / `ignore-errors` at
compile time** (no condition objects in its value model).

wasm-GC catching uses the WebAssembly exception-handling proposal and is GATED: only a program
containing one of the three catching forms is compiled in **EH mode** (one `$lisp-cond` tag,
`try_table`/`throw`) and only such a program needs wasmtime 37+. Anything else is byte-identical to
a build that never knew about EH -- unless `--report-locations` asks for the uncaught report
("Location lines on wasm-GC" below).

- **A NON-LOCAL EXIT is not a condition**: it passes through a `handler-case` uncaught while still
  running every `unwind-protect` cleanup -- cross-lambda `return-from`/`go` and `catch`/`throw`
  share one exit channel ([do-return-block.md](do-return-block.md), which owns the
  `ctx.blockExitTag`/`blockExitChannel` gate).
- **The three-point catchability spectrum**: interpreter catches `LispEvalException` only -- with
  the evaluation seam classifying an escaping `IllegalArgumentException` /
  `IndexOutOfBoundsException` (`program-error`) and cast / arithmetic / negative-size failure (the
  raw-failure classes) into one first, and only an `UnsupportedOperationException` (a limitation)
  left raw ("Argument-shape errors" below) -- JVM any `RuntimeException`, wasm-GC only `$lisp-cond`
  throws -- raw traps there (failed ref.cast, integer divide by zero, `unreachable`) are uncatchable
  and skip unwind-protect cleanups.

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
- **A special `let` IS a region of this machinery**: `Jvm`/`WasmLetCompiler` compile the body
  through `JvmUnwindProtectCompiler.Region` / `WasmUnwindProtectCompiler.compileRegion` with the
  internal `%dyn-restore` forms as cleanups, so the dynamic-binding restore rides every channel
  above -- and a binding and a cleanup nested either way unwind innermost-first
  ([dynamic-special-variables.md](dynamic-special-variables.md)). `internalOnly` exempts those
  cleanups from the `%mv-spill` save like `%hc-depth-dec`.

## Phase 2 -- condition objects
A condition is a CLOS-subset instance ([instance-syntax.md](instance-syntax.md)):
`(%obj-new '%class-<name> slots...)`, printed `#<NAME :SLOT value ...>`, NOT a list.

- `ClosRegistry`'s constructor seeds the hierarchy from `CONDITION_SEEDS`, ONE static list with two
  consumers that must keep READING it, never a copy: the constructor, and
  `PackageRegistry.CL_CONDITION_TYPES` = `ClosRegistry.CONDITION_CLASS_NAMES`, which makes every
  seeded name a `cl` symbol ([packages.md](packages.md)).
- Eight classes carry `format-control`/`format-arguments` beyond CLHS's slot lists -- `type-error`,
  `arithmetic-error`, `program-error`, `reader-error`, `package-error` (which also carries its
  `package` designator), `file-error` (`[PATHNAME, FORMAT-CONTROL, FORMAT-ARGUMENTS]`, the
  pathname read by the prelude `file-error-pathname`) and the two `cell-error` leaves -- because
  that pair is how a BUILT-IN error carries its message. `simple-type-error` therefore adds nothing, so both keep their old
  `%obj-ref` indexes. `stream-error` carries the offending `stream` (read by the prelude
  `stream-error-stream`); `end-of-file` inherits it, `reader-error` declares its own ahead of the
  message pair (`[STREAM, FORMAT-CONTROL, FORMAT-ARGUMENTS]`) with `stream-error` as its second
  ancestor -- the lite-multiple-parents rule applied to a seed (`reader-error` is both a
  `parse-error` and a `stream-error`, CLHS 9.1.2). A seeded class's hand-built factory instance
  (`newEndOfFileCondition`, `newReaderErrorCondition`) must mirror its seed's slot order exactly;
  the report partition groups by slot position, so a drift reads the wrong slots instead of
  failing (2026-09-16: the seed without `STREAM` grouped `reader-error` with `simple-error` and
  its report `cdr`ed the message).
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
  text in `format-control` -- as its TEXT CONTROL, below -- and nil `format-arguments`.
  **Re-evaluate if** the renderer becomes free.
- **Invariant: rendered TEXT enters a `format-control` slot only as its text control** -- every `~`
  doubled, the control whose rendering is the text verbatim -- so the report, which renders the slot
  as a control, prints it exactly once. Producers: `LispMacroExpander.textControlForm` (a literal
  escaped at expansion, anything else `(%text-control x)`) at every expansion that builds a simple-*
  instance, `reportingConditionForm` (the JVM pads, `%program-error`), `lowerFileError` and the
  JVM/wasm pads' `simple-error` synthesis; `ClosRegistry.textControl` on the interpreter's Java side
  (`newReportingCondition`, the reader-/file-error factories, `synthesizeCondition`); the wasm-GC
  fixed runtime's `emitConditionThrow` (dispatcher and operand landings) through `_tilde`
  (`FUNC_TILDE`, one body for `%text-control` and `%control-text`, shaken when unused). An
  `(error <literal>)` the expander builds from a name the program chose (an
  undefined function stub) goes through `textDatum` for the same reason: a string datum IS a control.
  Consequences, pinned by ci-spec `condition-report-prints-rendered-text-once` and the three
  `aConditionCarryingRenderedTextReportsItOnce` tests: `(simple-condition-format-control e)` of
  `(error "a~~b")` is `"a~~b"`, the control the program wrote, and `(apply #'format nil control args)`
  reproduces the report for every condition. Before (2026-09-26): the report re-rendered the text,
  so `(error "a~~b")` printed `aNIL` on the interpreter and a caught `(error (format nil "~a" "~/x/"))`
  ran the `~/` directive and escaped the handler as `The function X is undefined` on all four
  backends. Cost: +417 B on a wasm-GC `handler-case` program (`_tilde` is 324 B), +229 B on its JVM
  class.
- The lite `#'error`/`#'warn`/`#'signal`/`#'cerror` WRAPPERS forward the datum only
  (`BuiltinFunctionWrappers.SIGNAL_FUNCTIONS`), so `(apply #'error c '(1 2))` drops the arguments on
  the compiled backends -- documented lite semantics, as for initargs.
- Channels: interpreter `LispEvalException` carries a nullable `condition()`; JVM `%error-cond`
  stores the instance into the emitted `private static ThreadLocal _condTl` and throws
  `RuntimeException(message)` (that field plus `_hcDepthTl` emitted only when used,
  `JvmLispCompiler.ConditionChannel`); WASM `%error-cond` traps like `%error`.
- **The JVM message local is shared per method but NOT past its scope** (`Ctx.errorMessageSlot`):
  once `allocTemp` hands that slot to a variable, the cache drops and the next error site takes a
  fresh one. A handler-case in the SAME method resumes after the throw, so a live variable in the
  slot read the message instead of its value -- ci-spec `find-class-metaobject-substrate` printed
  its condition report in place of a `T` once `.todo/919`'s corpus case shifted the slot layout
  (2026-09-22). Pinned by `JvmLispCompilerTest#anErrorCaughtInTheSameMethodLeavesTheSpilledArgumentsAlone`;
  the fix moved 26 of 375 measured JVM artifacts (slot indices, at most +183 bytes of `max_locals`).
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
  raw conversion, exactly as a `print-object` method does. The string-designator path renders
  EAGERLY, and its text reaches the instance as its text control (Phase 2), so printing renders it
  once: `(error "~a" "~a")` prints `~a`.

## The condition floor is narrowed to what the program can construct
The compile path shrinks the condition runtime along three axes; the interpreter never narrows, and
every narrowing is IMPOSSIBILITY-based.

- **`conditionNarrowing`** scans the expanded program for the constructible `%class-` tag set plus
  whether any site can hand `%format-condition` an UNRENDERED control. Tag sources: literal datums of
  the signal family, literal-tag `%obj-new`, plus always the synthesized simple-* three. BAILS to
  `none()` on a computed datum, `eval`/`symbol-function`/`fdefinition`, an escaping `#'error`-family
  value or quoted designator in data, `--dynamic`. Name forgery from computed strings
  can reach a pruned arm -- the failure is the caller's fallback report text, never a lost signal.
- **Restart mode narrows like any other program** (since 2026-09-26; the bail it replaced had no
  stated reason). What it adds is seen by the scan or always in the set: the signal hook builds the
  simple-* three over a text control, restart-mode `cerror` keeps its datum inside a `restart-case`,
  and the restart runtime defuns are injected before the scan. `usedLayoutTags` dropped its
  restart-mode bail on the same grounds. A handler-bind whose handler prints its condition,
  `(car 5)` probe, wasm-GC default optimize: **113,391 -> 26,365 B** (component 117,075 -> 27,911; JVM
  output 106,911 -> 38,728 B); the same handler ignoring its condition 18,094 -> 16,614 B (the
  layout half). Output unchanged on all four backends over 43 probes covering every late-lowering
  site and raw failure under a bare handler-bind.
- **`%format-condition` declines the renderer** when every possible control has no directive but
  `~~` -- a literal whose every `~` is half of a `~~`, nil, or a `(%text-control x)` -- with nil
  arguments: the common case, since every string-datum signal site pre-renders
  (`formatMessagePieces`) and stores the text control. The declined arm is `(%control-text control)`,
  which undoubles the `~~`: the one directive such a control can hold, so declined and rendered
  artifacts print the same text. Only an explicit `:format-control` initarg with another directive
  (surface keyword check plus the baked `:initform`/`:default-initargs` cons check inside generated
  constructors' `%obj-new`) forces the renderer back. On zlib that is **-61 KB**.
- **A tag built by a LATER lowering is taken from its site's presence.** The read family's
  `end-of-file` (`expandReadEofSignal`, expression expansion) and a failed open's /
  `%file-error`'s `file-error` (`lowerFileError`, body compilation, behind a pad only) are
  constructed after both scans, so `conditionNarrowing` and `usedLayoutTags` read
  `LispMacroExpander.END_OF_FILE_SITES` / `FILE_ERROR_SITES` instead. The narrowing used to
  miss `end-of-file` entirely: a caught one `princ`ed as `#<END-OF-FILE :STREAM NIL>` on the
  compiled backends instead of `end of file` (found and fixed 2026-09-19).
- **`WasmInstanceLayouts.emit` takes a used-tag set** (`usedLayoutTags`): a `%class-`/`%struct-`
  layout ships only when its tag or bare name occurs as a symbol in the final program (plus the
  simple-* three the handler lowering synthesizes during Pass 2), with null (= bake all) under
  `--dynamic`, an embedded eval runtime, subclass enumeration,
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
- **`handler-bind` counts unless every handler is a literal `lambda` whose body never mentions its
  first required parameter** (`handlerBindExposesCondition`): a handler is CALLED with the instance,
  so a `#'name`, a computed handler or a lambda list opening with `&rest`/`&optional` counts. Before
  2026-09-26 it did not count at all, and on the compiled backends `(format t "~a" c)` in a handler
  printed `#<TYPE-ERROR :DATUM 5 ...>` while the interpreter printed the report. The `(car 5)`
  probe on wasm-GC: 16,614 B with a handler that ignores its condition, 26,365 B with one that
  prints it (113,391 B until restart mode was narrowed, above).
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

- **`WasmErrorCompiler.compileCond` / `WasmSignalCondCompiler` never compile the REPORT half of their
  message operand**: those forms always carry a real instance in the car, and the entry pad renders
  its report itself. `compileCond` compiles only what the pad cannot rebuild -- the fallback of a
  routed message (`LispMacroExpander.conditionReportFallback`, the recognizer of
  `conditionReportOr`'s own shape), below "An uncaught condition reports ONE line". The instance
  operand still compiles (initargs are evaluated at construction per CL).
- **A plain `%error`'s message operand compiles only when `Ctx.condMessagesObservable`** -- its
  message IS what a caught raw trap becomes a `simple-error` from AND the only text the entry landing
  pad has. Forced on under restart mode / `--dynamic` / EH mode; copied in `WasmAsyncEmit.freshCtx`.
- **The routing gate narrows outside EH MODE**: `expandTopLevelDefinitions` takes a
  `macro/SignalMessages` (`WasmLispCompiler` passes `LAZY` when `!reportsUncaught`), under which the answer is
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
**Invariant: a signaled condition escaping the top level writes `Unhandled condition: <report>` to
standard error -- the same line on all four backends -- then the process exits the way it always
did.** Built from `compiler/UncaughtReport.PREFIX` at all three emission sites; the report text is
the one `princ` writes, and nothing below changes it. One report still differs on wasm-GC (the
report-less class, "Known gap" below). A struct accessor on a non-instance agrees since 2026-09-26
([defstruct.md](defstruct.md), "Accessors check their object"), and so does an out-of-range
subscript ("An out-of-range subscript is a type-error naming its bound").

**Under it, location lines** (`UncaughtReport.atLine` / `asyncLine`, two-space indented):
`  at FILE:LINE in FUNCTION` -- the innermost form read from a named file that the condition passed
through and the PROGRAM'S named function that form is written in -- then one
`  in NAME (async), awaited at FILE:LINE` per async boundary crossed. Nothing known (a `-e`
program, only macro-built forms) prints none. **The interpreter and the JVM backend print the same
lines, for Scheme source too** (`cli/UncaughtReportParityTest`); wasm-GC prints them only under
`--report-locations` (below, "Location lines on wasm-GC").
- **Which function: LEXICAL** (every backend) -- the named function whose text holds the form. A
  form in an anonymous lambda (a callback, an `flet`/`labels` helper, a `handler-bind` handler, a
  sort predicate) belongs to the function the lambda is written in, whoever calls it; one at the
  top level, or in an async body (its hop line names the async function), to none. Only a
  function whose body was read from a named file is named (`LispLambda.sourced`,
  `JvmSourceSites.sourced`), so a library function a callback runs under never is. The answer is
  a property of the FORM, so no backend's tail calls, inlining or threads can change it -- the
  reason it is lexical. Until 2026-09-26 it was the named function DYNAMICALLY around the form,
  which each backend approximated from the frames it still had: the interpreter lost the caller
  on a tail call (then kept it, `frameFunction`), the JVM never lost it, and a wasm-GC
  `return_call` left it -- one program named three different functions, and a callback written
  in MAIN but run by RUN-CALLBACK printed `at <a line of MAIN> in RUN-CALLBACK`. A lowering's
  internal name is reported as the program spelled it (`UncaughtReport.functionName`): a method
  body is its generic (`%AREA--m0` -> `AREA`), a `%top-defun$` rename its original, a nested
  `defun` its own name (`UncaughtReport.nestedDefun`).
- **Interpreter -- recorded on the throw path only** (`eval/ConditionTrace`, on
  `LispEvalException.trace()`): `evalCons` keeps the innermost `LocatedCons` it stepped onto and
  the lexical function of the scope it stepped onto it in (a type test and two stores per loop
  step; [source-positions.md](source-positions.md) Phase 4) and hands them over from the catch
  clauses it already had; the first frame with a located form decides both. The function is a
  field of the scope (`Environment.lexicalFunction`): a program function's call scope and a macro
  expander's set it, every other scope -- a closure's included -- inherits it from its parent. A
  form `eval` runs is in the null lexical environment, so it names none.
- **Interpreter async**: `evalCons` runs `%async-run` itself (`runAsync`), naming the async
  function from its own frame -- the body's virtual thread never sees the defun -- and runs the
  thunk under a scope of no function (`asyncBody`). A condition escaping the thunk closes segment
  0 (`crossedAsync`), so the awaiter's frames are not attributed to it; the first located form
  after that is the `await`.
- **The await that re-signalled it** (every backend, since 2026-09-26): a failed future holds ONE
  condition object, which every `await` of it rethrows, so each await first rewinds the trace to
  what that future stored -- the hop's site back to none, and every hop an earlier await's path
  appended dropped. A second await after a handler caught the first names ITSELF, and one of JOB's
  future after RELAY's (which re-signalled JOB's condition) was caught prints no RELAY hop. The
  interpreter keys the hop by the future's `CompletableFuture` (`ConditionTrace.reawaited`, from
  `joinFuture`). Until then the interpreter and the JVM recorded the site once per crossing and
  named the FIRST await, while `--component` named the right one only when no hop had been added
  since.
- **JVM -- read off the stack trace** (`codegen/jvm/JvmSourceSites`, `JvmUncaughtHandler`): every
  method the program's own source compiled into carries a `LineNumberTable` whose numbers are SITE
  ids -- (file, line, function) in a table the class carries as string constants -- not lines: a
  method holds several files' forms (a top-level chunk, a macro handing back a form of its own
  file) and a class names one `SourceFile`. `Ctx.enterSite`/`leaveSite` around every form
  (`JvmExprCompiler.compileCons`, the statement `setq`/`let`, the fused `if`/`while` test) mark
  where the innermost located form changes; a site's function is its method's `writtenIn` -- a
  lambda's method takes that of the method that built it (`LambdaInfo.writtenIn`), an async thunk
  none; a tail-spine item carries the site current where it was queued (`JvmBodyOutliner.Entry`
  -- the construct that queued it has returned by then), and a `_k$N` continuation inherits its
  method's. `_where` walks the exception's trace: the innermost frame of this class (or a
  `$PartN`) at a site gives the location and, from the same site, the function. The positions are
  `SourceProvenance`'s, the same forms the interpreter's reader locates. (A function's methods
  used to open on a BASE site naming it, for the dynamic rule's outward search; the lexical one
  needs none, 4 bytes a method.)
- **JVM async**: the `%async-run` thunk's last exception entry appends a made-up frame
  `rontolisp/async.crossed(HEAD)` to the escaping exception's trace (`_asyncCross`; `/` is in no
  binary name); `run()` keeps the trace as it then is in the error payload, a fourth element
  (`{EMARKER, t, cond, trace}`, only in a class that records boundaries), and each `_await` that
  rethrows it puts that trace back and appends its OWN frames after the boundary
  (`_asyncAwaited`). `_where` reads each such frame as a hop. An exception keeps its identity (`handler-case` classifies by class), and
  the report empties the trace, so none of it shows unless `RONTOLISP_DEBUG` asks for the trace --
  which then IS the async chain.
- **JVM optimizations keep the granularity**: a fused integer tree (`_fx$N`,
  [jvm-int-fusion.md](jvm-int-fusion.md)) whose operations all report the call's own site stays
  shared and line-free; one that spans lines, or holds an inlined defun's body, gets a method of
  its own whose fallback marks each operation (so `(sq a)` inlined into F still says `in SQ`, even
  when F itself was inlined into the top level). A typed loop marks its array accesses.
- **Nothing located, nothing emitted**: a class compiled with no position in a named file (`-e`,
  a direct `JvmLispCompiler.compile` with no recording scope) has no line numbers, no site table,
  no `_where` and its pool in the old order -- the bytes it always had
  (`UncaughtReportParityTest#aProgramWithNothingLocatedCompilesAsItAlwaysDid`). The line numbers
  survive every pass after emission: `BranchRelaxer` remaps them, `JvmClassShaker`,
  `JvmClassSplitter` and `StackMapAugmenter` carry the attribute (no instruction moves there).
- **Cost, measured 2026-09-26**: +1.5-2 KB per class (`_where` and its constants; ~1 KB gzip)
  plus ~7 bytes per located line (examples/console +1.46-1.79 KB; `llm.lisp` 1.04 MB +12.6 KB,
  1.2%); a fused tree that needs its own method ~200 B. Zero at run time until a condition
  escapes.
- **Harness decisions, per suite**: `ci-spec.yaml`'s `standalone:` compares expected stderr lines
  as CONTAINED, in order (wasmtime prints around ours), so the location lines need no change there
  and are pinned instead by `RontoLispCliStreamsTest`'s `anUncaught*` cases (the interpreter, then
  `java -cp` and `java -jar` of the compiled program) and `UncaughtReportParityTest`, where the file
  path is the test's own. `scheme-spec.yaml` compares the interpreter's MESSAGE and the compiled
  legs' stderr as CONTAINED, so it needs no change; the Scheme cases in `RontoLispCliStreamsTest` pin
  the location lines where the case is about the report and the report line alone where it is about
  the message. The JVM/wasm assertions that compile directly (`JvmLispCompilerTest`,
  `JvmSizedMainTest`, `WasmLispCompilerIntegrationTest`) record no positions -- their programs are
  read from no file -- so they pin the report line alone, a wasm module's even under the option.

- **Interpreter / compile failures** (`RontoLispCli.runReporting`): only `main` catches -- `run`
  still throws, so an embedded caller keeps the exception with its type and cause. A rontolisp
  diagnostic (read error, compile failure, bad command line) says `error:` instead and keeps the
  `file:line:column:` prefix (`locateCompileFailure`). **A RUNTIME condition's MESSAGE carries no
  position on any backend**: the location goes under the report, never into the text a program
  can read. `RONTOLISP_DEBUG` additionally prints the JVM trace.
- **JVM** (`JvmUncaughtHandler`): a last exception-table entry over the whole of `main` catching
  `RuntimeException`; prints the line (and `_where` the location lines), EMPTIES the stack trace
  and RETHROWS. **Not
  `System.exit(1)`**: a compiled class's `main` is invoked in-process by ~110 assertions here and by
  any embedder; the launcher supplies exit 1.
- **wasm-GC, EH MODE ONLY** (`WasmUncaughtReportCompiler`): the entry function wraps its body in
  `block $trap` + `block $cond (result (ref null eq))` +
  `try_table (catch $lisp-cond 0) (catch_all 1)`; the landing takes the payload as the inner block's
  result, splits it into `__uc_cond$N`/`__uc_msg$N` and compiles
  `(%warn (%string-concat "Unhandled condition: " (if cond (or (%condition-report-str cond) msg) msg)))`,
  guarded by `(let ((v ...)) (if v v ""))` so a nil never renders as `NIL`; `%warn` is the
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
- **Outside EH mode nothing changes, byte for byte**, unless `--report-locations` turns the report
  on (`SignalMessages.ENTRY_REPORT`, below); reporting there by default means turning EH mode on for
  every program (121,572 -> 175,486 B on the two-line toy, 2026-08-14). `--no-gc` is exempt outright; a
  `--no-wasi` reactor compiles the pad but writes into the discarding `fd_write` sink, which is why
  `doc/{en,ja}/guides/wasm-gc-module.md` still says a load-time failure there is a bare
  `RuntimeError: unreachable`. Worst-case cost on zlib `--optimize=size`: 72,837 B ->
  `condMessagesObservable` 76,812 -> broad routing gate 85,391 (+17.2%), taken deliberately since
  the first step alone printed `Unhandled condition: ` and nothing else. Narrowing it to the
  classes that can ESCAPE buys nothing: without a catching form every constructible class
  escapes, which is what `conditionNarrowing` already keeps. What `ENTRY_REPORT` narrows instead
  is the printing operators (below). The function-control arm narrows the same way in EVERY
  mode (`.todo/988`, below): a declined renderer drops it whether or not `--report-locations`
  is the reason EH mode is on.
- **A class that reports nothing is reported by its SIGNAL SITE** (`(define-condition c (error) ())`,
  a bare `type-error`, `simple-error` with no control): `%condition-report-str` answers nil, and the
  text -- `Condition (C :INITARG v) was signalled.` for a typed signal, `Condition of type C was
  signalled.` for `(error c)` -- is the fallback half of the site's `conditionReportOr` message, which
  `compileCond` compiles into the payload cdr and the pad prints when the report is nil. **The text is
  built in ONE place, the expansion** (`legacySignalMessage`, `expandObjectSignal`'s `typeMessage`),
  and every backend prints what it evaluates to: a change to its shape changes all four together.
  Chosen over a fallback arm in the pad because the pad sees only the instance, and the typed text
  names the initargs AS WRITTEN (`:DATUM "abc" :EXPECTED-TYPE INTEGER`), which a built instance
  cannot reproduce. A class that INHERITS a report builds no fallback at all
  (`inheritsConditionReport`): it always renders, and the fallback was dead code on every backend.
  Measured 2026-09-26 on e6e49385b (EH mode, bytes): zlib `--optimize=size` 89,623 -> 89,734 (+111),
  `--optimize` 117,008 -> 117,119, component 93,712 -> 93,819; `postgres-hello --component
  --optimize` 2,708,177 -> 2,700,709 (-7,468, the dead fallbacks), `postgres-crud` 2,783,155 ->
  2,776,452; a toy `(error 'c)` 22,547 -> 25,917 -- the `~s` of the initarg list brings the
  printer.
- The `(error c)` text named the class through `prin1` of its `%class-` tag, which escapes since
  symbols print with `|...|`: `Condition of type -C| was signalled.` on the interpreter and the JVM
  until 2026-09-26; it reads `symbol-name` now.
- Pinned cross-backend by `ci-spec.yaml`'s `standalone:` list -- a section `CiSpecE2eTest` runs one
  program at a time, per backend; the corpus cannot host these since running one ends the program.

## Location lines on wasm-GC (`--report-locations`)
**Invariant: `--report-locations=line` makes a wasm-GC module (Preview 1, `--component`,
`--native`) print the interpreter's report and location lines, byte for byte -- turning the report
on for a program outside EH mode too; `=function` prints the function and the line its definition
starts on. OFF BY DEFAULT, and a module with no file-read form (`-e`), no option, or no
report anywhere to print to (a `--no-wasi` reactor outside EH mode) is byte-identical to a build
that never knew about it.** `codegen/wasm/WasmUncaughtLocations`; pinned
by `cli/WasmReportLocationsTest` (each case against the interpreter's own output) and
`e2e/NativeOutputE2eTest`.

- **Opt-in because size decides it** (the user's call, 2026-09-26): the lines cost bytes in every
  function read from a file, and outside EH mode the report itself costs the EH machinery.
- **Outside EH mode the option turns EH mode on, as `SignalMessages.ENTRY_REPORT`**
  (`WasmLispCompiler`'s `entryReportOnly`: the option, no EH trigger, and a located form --
  `WasmUncaughtLocations.readsAnyFile` -- but never a `--no-wasi` reactor, whose standard error is
  a discarding sink). The entry pad is then the only reader of a condition, so
  one thing EH mode proper carries is dropped: the printing operators route a condition
  through its report only when program code can HOLD one (`ClosRegistry.printsConditionReports`,
  the `mayHoldConditions` answer) while the renderer itself stays on the `mayCreateConditions` one
  (`routesConditionReports`).
- **A declined renderer drops `%format-condition`'s FUNCTION-control arm in every mode, not only
  under `ENTRY_REPORT`** (`.todo/988`, 2026-09-26): the arm is a funcall of a runtime value, which
  in a program that can make a symbol at run time (`read`) keeps every built-in dispatchable by
  name, whatever narrowed EH mode is on for. The first version of this narrowing kept the arm in
  plain EH mode (`SignalMessages.RENDERED`) to hold `--report-locations` byte-identical to a build
  without the option; the user approved dropping that condition for this change and its size
  effect is recorded here instead. `(print (read))` plus `(ignore-errors nil)`,
  `--optimize=size`: 245,858 -> 47,570 B (`without the catching form`: 39,027 B, unaffected either
  way). Under `ENTRY_REPORT` (`--report-locations=line`, no catching form) nothing moves --
  42,326 B before and after -- since that arm was already dropped there. `zlib` (chipz, EH mode via
  `catch`/`throw`, no option): `--optimize=size` 89,499 -> 89,326 B (-173, -0.19%), `--optimize`
  116,920 -> 116,513 (-407), `--optimize=off` 462,894 -> 462,464 (-430), `--component --optimize=size`
  93,557 -> 93,382 (-175); gzip -9 `--optimize=size` 29,971 -> 29,924. `hello_world`/`pi_approx`
  `--optimize=size` (no catching form, not in EH mode) unchanged: 480 / 1,489 B. Measured on this
  worktree's HEAD (`ea5e69304` + the change), wasmtime 49.0.0, Linux.
- **A frame** = a defun, lambda, top-level chunk or `--component` resume whose OWN code (outside
  the lambdas it builds) has a form read from a file. It wraps its body in `block` +
  `try_table (catch $lisp-cond)`; the landing calls `_uncaught_note(payload, file-id, line, name,
  hop)`, which records and hands back the SAME payload, and rethrows it. Library source, macro
  output and the Preview 1 `(%async-run (lambda ...))` wrapper defun are not frames, exactly as the
  interpreter's frames without a located form note nothing -- except an ASYNC BODY with no located
  form (`rontolisp:then`'s async lambda, a library `async-defun`), a frame that notes its hop and no
  position (`Spec.hopOnly`): the interpreter prints that hop, with no await site when the await
  that crossed it is library code too.
- **The note is keyed by payload IDENTITY** (a global holding the payload it describes): a
  condition a `handler-case` caught cannot leak into a later report, and a rethrow (unmatched
  clause, `await` re-signalling a rejected future -- both rethrow the same payload) keeps the
  inner frames' note. Rules = `ConditionTrace`'s: the first frame with a line gives the location
  and, by its own name, the function (a lambda's frame is named after the function it is written
  in, `Ctx.ucWrittenIn`; a nested `defun`'s after itself); an async body (a hop text in the
  frame) appends a hop whose await site is the next frame with a line. So every rethrow keeps the
  payload: `%hb-guard` stores the instance it synthesized into the payload it caught rather than
  consing a new one (under the option only, so the bytes without it stay), which lost every line a
  `handler-bind` handler's own frame had noted.
- **A landing pad inside a frame notes first** (`notePad`): its code moves the line local, and its
  refresh ([wasm-landing-pad-refresh.md](wasm-landing-pad-refresh.md)) would put the local back to
  the region's entry -- so the frame's catch saw the `unwind-protect`'s, the special `let`'s or the
  unmatched `handler-case`'s line instead of the signalling form's. The position locals (line,
  file, owner: i31s, which the collector never moves) stay out of the pad's push
  (`positionSlot`); the pad notes what they held at the throw, then sets them to what the static
  track says (`resyncPad`) for the code after it. A caught condition's note is harmless (keyed by
  identity); a later await rewinds it (below).
- **An await rewinds the note** (`--component`, and Preview 1 over its failed futures): the poll of
  a rejected future (`_p1_future_await` of a failed one) calls `_uncaught_reawait(future, payload)`
  before re-signalling. The FIRST await of a future records a snapshot -- `(last-hop site file line
  . name)` -- in an association list keyed by the future (nothing but an await of it can have noted
  its payload since the rejection); each later one restores it. A fresh note clears the list.
- **Texts ride with their frame**: the name and hop text are unspelled string literals built in the
  frame's own landing (`compileUnspelledLiteral`: a spelled one would arm the dispatch gate), so
  the shaker drops them with the frame. The name is the program's spelling
  (`UncaughtReport.functionName`, the mapping every backend uses: a method body names its generic).
  A first version kept a quoted name TABLE: every defun's
  name stayed after its function was shaken -- zlib +4,489 B instead of +3,856. Files are the one
  table (quoted list, sealed when the entry's render compiles; the prescan of the program is what
  makes it complete, since every Pass 2 rewrite inherits an existing cons's position).
- **The line**: an i31 in an eqref local (mirrored by a resume's spill array, so it survives a
  suspension), set on entry to a located form when it holds another value and restored after it
  (static tracking in `Frame.curLine`; the function's tail form restores nothing). Starts NULL, not
  at the definition line: a frame that entered no located form leaves the location to its callers,
  as the interpreter does. Printed by `_uncaught_digits` (i31 -> string via `_str_fresh`): the
  general printer (`%princ-to-string`) pulled 20 KB into a module that never prints
  (`examples/net/http-handler.lisp` 2,171 -> 23,868 B; now 2,769).
- **Tail calls**: a `return_call` leaves the try_table. Inside a frame a direct one stays one only
  into another frame (`tailCallOp`); one through a function value tests the callee at run time
  (`emitValueCall` -> `_uc_frame_p`, a test over the frames' funcId ranges or a `br_table` when
  shorter, written once Pass 2c knows every frame): into a frame a `return_call`, into anything
  else (a built-in, library code) a plain `call`, so the frame notes the `funcall` the interpreter
  would. An async body's frame never tail-calls out: it must open its hop. Disabling tail calls in
  frames outright was the first version and broke the `.kb/wasm-tail-calls.md` invariant a Scheme
  loop depends on (named `let` overflowed); a plain `call` through every function value would too.
- **A fused integer tree** ([wasm-int-fusion.md](wasm-int-fusion.md)) is one form to the frame,
  but its fallback -- the one path where an operation signals -- marks each operation
  (`enterOperation`): its own line, and for one from an inlined defun's body that defun, through an
  owner local (an i31 index into the frame's list of inlined functions) the frame's catch
  dispatches on for file, name and, under `function`, definition line. The fast path bails
  rather than signalling, so it marks nothing.
- **Preview 1 async**: an EH-mode module settles a FAILED future when the body signals, and the
  await rethrows its payload ([async-await.md](async-await.md)), so the hop names the await's line
  as on every other backend.
- **Measured 2026-09-26** (macOS arm64, wasmtime 49): the 35 single-file programs of
  `UncaughtReportParityTest`, `RontoLispCliStreamsTest` and this suite, plus 39 aimed at the shapes
  above (multi-file and cross-file inlining among them), each with `(print (ignore-errors nil))`
  appended -- every location line matches the interpreter's on Preview 1 and `--component`. What
  still differs is not a location: a raw trap (a typed loop's out-of-range `aref`) prints no report
  at all, the three-point spectrum above; a `handler-bind` handler that fails in a built-in reports
  a different condition on each backend (`.todo/994`); and `~a` of a handler's condition prints its
  slots on the JVM and wasm-GC (`.todo/995`).

Cost in EH mode, measured 2026-09-26 on 2f611be61 plus `.todo/991`'s change (wasmtime 49.0.0,
node 24, macOS arm64); the change itself cost zlib `--optimize=size` +351 B `function` / +358 B
`line`, `--optimize` +351 / +666 (the fused fallbacks' marks), the 101 functions +116 (a `throw`
in each landing where `unreachable` was), the one-function programs +14-15:

| module | flags | off | `function` | `line` |
| --- | --- | --- | --- | --- |
| hello_world + `(ignore-errors nil)` | `--optimize=size` | 1,214 | 1,361 | 1,386 |
| pi_approx + `(ignore-errors nil)` | `--optimize=size` | 2,293 | 2,439 | 2,512 |
| zlib (chipz, 51 frames) | `--optimize` | 116,920 | 122,115 (+4.4%) | 128,129 (+9.6%) |
| zlib | `--optimize=size` | 89,499 | 94,685 (+5.8%) | 100,626 (+12.4%) |
| zlib | `--component --optimize=size` | 93,557 | 98,717 | 104,665 |
| zlib | `--optimize=off` | 462,894 | 468,828 | 476,221 |
| 1 three-line function | `--optimize=size` | 7,426 | 8,057 | 8,107 |
| 101 three-line functions | `--optimize=size` | 14,092 | 18,754 | 20,765 |

zlib's `line` column was 93,774 (+6.6%) when first measured the same day: the growth came in with
upstream commits before 2f611be61 (that commit alone gives 100,268), not from this table's change.

Outside EH mode (`ENTRY_REPORT`), measured 2026-09-26 on da01b1a51 (wasmtime 49.0.0).
"forced" is plain EH mode forced by the option (`RENDERED`), the first shape tried:

| module | flags | off | forced (`line`) | `function` | `line` |
| --- | --- | --- | --- | --- | --- |
| hello_world | `--optimize=size` | 480 | 660 | 649 | 660 |
| hello_world | `--component --optimize=size` | 1,635 | 2,000 | 1,989 | 2,000 |
| pi_approx | `--optimize=size` | 1,489 | 1,779 | 1,740 | 1,779 |
| pi_approx | `--optimize` | 2,410 | 2,697 | 2,658 | 2,697 |
| `(parse-integer (read-line))` in a defun | `--optimize=size` | 6,350 | 12,583 | 12,566 | 12,583 |
| a typed condition, inherited `:report` | `--optimize=size` | 2,898 | 26,829 | 20,216 | 20,232 |
| a typed condition, inherited `:report` | `--component --optimize=size` | 3,882 | 30,733 | 24,056 | 24,072 |
| `(print (read))` | `--optimize=size` | 34,981 | 245,561 | 42,396 | 42,404 |
| `(print (read))` | `--optimize` | 35,920 | 300,416 | 43,324 | 43,332 |

- The rest is what a report needs: the throw path and the pad (~170 B on hello_world, where
  nothing can throw), and each signal's message -- `parse-integer`'s names its string, so the
  string printer comes in (+6.2 KB). The typed toy's `off` is a folded trap.

- Fixed: ~140 B (the note and digit helpers, the render). Per frame: ~39 B raw / ~9.5 B gzip under
  `function` (the try_table wrapper is 12, the note call ~20, the name ~5 of data); `line` adds
  ~6.5 B per line change (3-line bodies: +20 B). gzip, zlib `--optimize=size`: 29,146 -> 30,416 ->
  31,221.
- Run time (bench-report programs + `(ignore-errors nil)`, best of 5 -- the EH mode the option now
  turns on by itself for these programs): **V8 within noise** (every
  program +-4%); **wasmtime: fib +220%**, matmul +13-15%, mandelbrot +8-13%, the rest 0-7%. The
  cost is the try_table around calls in Cranelift (`function`, which sets no line, pays the same),
  so a tiny recursive function pays the most. **Re-evaluate if** wasmtime's exception lowering
  stops spilling across calls inside a try_table -- or if the option is ever meant to stay on in
  production, in which case the per-call catch is what to replace.

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
- **The six operators the lowering serves**: `restart-case`/`restart-bind`/`with-simple-restart`
  expand via `LispMacroExpander.expandRestartCase`/`expandRestartBind`/`expandWithSimpleRestart`;
  `find-restart`/`invoke-restart`/`compute-restarts` are runtime defuns assembled by
  `LispMacroExpander.restartRuntimeForms` (injected by `expandTopLevelDefinitions` at compile time,
  `ensureRestartRuntimeLoaded()` at interpret time).
- **Two dynamic stacks, both TOP-LEVEL GLOBALS** (`%HANDLER-CLUSTERS%`, `%RESTART-CLUSTERS%`,
  injected as `defvar`s; plus `%HANDLERS-RAN%`, the completed-walk mark), mutated with plain `setq`
  and restored through an `unwind-protect` cleanup over a LEXICALLY saved value. Plain `setq` +
  cleanup rather than special-`let` rebindings, chosen when the compile paths still skipped the
  special-binding restore on the error-throw, `catch`/`throw` and cross-lambda `return-from`
  channels. Those holes are closed -- a special `let` is now itself an unwind-protect region
  ([dynamic-special-variables.md](dynamic-special-variables.md)) -- so a `let` would work too; the
  `setq` shape stays because it is pinned cross-backend and a rewrite buys nothing.
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
- **A handler's own call runs in a pad too** (`runHandlersDefun`: `(%hb-guard (funcall handler
  c))`), while `%handler-clusters%` holds the REMAINING clusters. CLHS 9.1.4.1 runs a handler with
  its cluster disabled, so a built-in failing inside it is walked there, against the enclosing
  clusters only, and marked; the handler-bind's own pad then rethrows it untouched. Without it the
  failure escaped the walk's cleanup with the full stack restored and the handler-bind's pad RAN
  THE FAILING HANDLER AGAIN on the `type-error` (JVM and both wasm-GC, until 2026-09-26). Pinned by
  ci-spec `restart-system` (the output) and `failing-handler-bind-handler-report` (the report).
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
  undefined` -> its cell-error class, because those sites are plain `RuntimeException`s emitted in
  bytecode with no channel to carry a class; a wrong-type operand's exception is recognized by
  identity instead (`_teSlot`, see "A non-number reaching arithmetic"). The arms are compiled Lisp forms built by `LispMacroExpander.reportingConditionForm`, so no
  slot index is baked here.
- **WASM**: the pad is unchanged, and correctly so -- only `$lisp-cond` throws land in it. **An
  undefined-function call diverges by CLASS rather than catchability**: catchable, but as a
  `simple-error`. **The stub cannot construct the typed instance**: it is produced during BODY
  compilation, after `mayCreateInstances` fixed whether the artifact has an instance representation
  and after `usedLayoutTags` chose which layouts to bake (`%OBJ-NEW reached the compiler with no
  instance representation`). **Trigger: teach both gates about undefined calls** -- the precedent is
  the non-number family ("A non-number reaching arithmetic"), whose `type-error` layout
  `usedLayoutTags` bakes on the pad's presence alone.
- **Undefined functions keep the call-time stub contract**: a call to a name with no definition
  compiles to `The function X is undefined` at call time plus a compile-time warning, matching the
  interpreter's late binding. It stays a STRING signal for the gate reason above.
- **The message a raw host failure reports is rontolisp's, not the host's**:
  `ClosRegistry.TYPE_ERROR_MESSAGE` replaces a `ClassCastException`'s Java class names and
  `INDEX_OUT_OF_BOUNDS_MESSAGE` the JVM's `Index 10 out of bounds for length 3` (whose length counts
  the layout cell in slot 0) -- now only for what still reaches the host's check (a string's
  index, `.todo/186`): an array subscript reports its bound itself ("An out-of-range subscript").
  Per-site texts a built-in writes itself are kept and are NOT identical across backends. The
  numeric operators name themselves by per-operator emission at the call ("A non-number reaching
  arithmetic"), never by message parsing at the pad. The substitution does NOT reach the UNCAUGHT
  top-level line on the JVM -- deliberate.
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
**Invariant: a wrong-type operand reaching a numeric operator signals a CATCHABLE error whose text
is `OP: The value <prin1> is not of type T` -- the operator and the type IT accepts, CL's
`type-error` shape -- byte-identical on all four backends, and the condition is a `type-error`
whose `type-error-datum`/`type-error-expected-type` answer the operand and `T`.** One table, `compiler/OperandTypes`: the named operators and their types (`NUMBER` for
`+ - * / = abs sqrt exp expt ...`, `REAL` for the orderings, `min`/`max`, the rounding family,
`mod`/`rem`, `float`, `random`, `complex`; `INTEGER` for the bitwise family, `gcd`/`lcm`/`isqrt`;
`RATIONAL` for `numerator`/`denominator`), narrowed to `REAL` where a `NUMBER` operator met a complex
that must be real (two-argument `atan`). The table's other operators are FUNNEL-TYPED ("A wrong-type
argument names its operator" below). A failure outside a named operator reports unnamed with the
funnel's own kind. Pinned by `ci-spec.yaml`'s `non-number-arithmetic-operands-are-catchable` and
`operand-type-errors-name-the-operator-wherever-it-compiles`; the wasm class by
`WasmLispCompilerIntegrationTest.ehANonNumberArithmeticOperandSignalsATypeError`.

**Detection stays at each backend's coercion FUNNEL; the operator is attached ONE LEVEL UP**, because
a funnel (and every shared helper above it -- `_cmpb` serves `< > <= >= = min max`) cannot know which
operator it serves:
- **Interpreter**: `Environment.asLong`/`asDouble`/`asBigInteger` and the real checks throw an UNNAMED
  `eval/OperandTypeException`; the built-in seam in `LispEvaluator.apply` names it after the built-in
  whose body raised it (`named`, no-op when already named or not a named operator), and
  `synthesizeCondition` fills `DATUM`/`EXPECTED-TYPE`. Built-ins that reach a funnel through
  `callGlobal`/`applyGlobalFunction` (bypassing the seam) name it themselves: the floor family's
  division (`floorFamilyQuotient`, `evalFloorFamilyDivision`), `requireRealOperand`,
  `roundToInteger`, `float`.
- **JVM** (`JvmOperandTypeRuntime`): every funnel throw is `_teRaw(x, kind)`. `JvmExprCompiler.compileCons`
  sets `Ctx.operator` to the form's head for the form's own emission; `Ctx.numOp` and
  `JvmComplexCompiler.complexOp` hand a wrappable helper (`JvmNumericRuntimeBuilder.wrappedDesc`)
  back as a per-(helper, operator) WRAPPER, built on first use: the same invocation under a catch-any
  entry whose handler throws `_opTypeErr(e, "OP", "TYPE")`, which renames an unnamed report and
  passes anything else through. Zero cost on the normal path (measured: no difference on a boxed
  generic-arithmetic loop). Under a landing pad the thread-local `_teTl` holds
  `{exception, datum, type}`, identity-checked by `_teSlot`, which is how the pad's `type-error` arm
  fills the slots -- a `RuntimeException` has nowhere to carry an object and a compiled program ships
  no exception class. Size: +0.8 KB on a four-defun class, +2.8 KB on a 116 KB one.
- **wasm-GC, EH mode** (`WasmOperandTypes`): a call to a helper that can reject an operand, compiled
  inside a named form (`WasmOperandTypes.emitCall`, converted at every call site of the arithmetic
  compilers and `castFloatGetF64`), stores the operator's id in the operator register (a
  `(mut i32)` global after the render guards) for the call's duration and clears it after; the
  `_type_err_*` landings read and clear it and look the id up in the operator table, a blob placed
  before any body compiles (**a string interned during emission lands after the data segment is
  fixed and reads back as blanks**), so it holds only the operators the program spells, their
  call-position rewrites, the `+ - * / = < > <= >=` family and the operators a lowering introduces
  (`WasmOperandTypes.LOWERED_TO`); an operator missing there reports unnamed. Boxing, the fused raw
  i64 helpers and fdlibm take no register (`mayReject`), and a call to a landing is not followed by
  the clear (a landing never returns and clears the register itself). Size: +2.3% on a 109 KB EH
  module; a non-EH module is byte-identical. Outside EH mode the landings stay a bare `unreachable`.
- **wasm-GC, one landing body**: `_type_err_int`/`_num`/`_real`/`_list` are 8-byte stubs handing
  their kind to the shared `_type_err(culprit, kind)` (`buildSharedLandingBody`, `TYPE_STR_TO_MEM`),
  which renders every report, so a module reaching several landings carries the rendering once; its
  type chain selects among only the codes a landing kind or a table row can produce
  (`Operators.rowCodes`), so a suffix no row reaches is never cited and drops with the string
  blob's dead ranges.
- **wasm-GC, the class**: the landings throw a `type-error` instance built in raw wasm
  (`WasmOperandTypes.TypeErrorShape`, `WasmRuntimeBuilder.emitConditionThrow` -- the
  `NotFunctionReport` precedent): `format-control` the report, `datum` the operand,
  `expected-type` the symbol the report names. The landing is a fixed helper built after the
  instance gates decided, so the GATE is the pad: EH mode behind a handler landing pad
  (`establishesLandingPad`) with instances on, and `usedLayoutTags` bakes `TYPE-ERROR` on the pad
  alone -- not on the program spelling `type-error`, because `type-of`, `class-of` and a
  `simple-error` clause observe the class without naming it. Without a pad nothing can observe the
  class and the landing throws the message-only `(nil . message)` payload, which the entry pad
  reports identically. **The type symbols are interned as their own names before any body compiles**
  (`Texts.typeNames`): a symbol's identity is its string-table entry, so `(eq (type-error-expected-type
  e) 'number)` holds only because the landing and the program's literal share it -- a view into the
  suffix text would print right and fail `eq`. Size: +106-116 B on zlib with a `handler-case`
  around its body (P1/component, default and `--optimize=size`, measured 2026-09-26); zlib as
  shipped has no pad and is byte-identical. A side effect: `NotFunctionReport`'s `type-error` for
  `(funcall 5)` is typed under any pad now, since it rides the same baked layout.
- **The operator is the innermost FORM being compiled**, so a lowering that re-emits an operator's
  calls away from its form sets it back: the fusion fallbacks per tree node
  (`JvmIntFusionCompiler.numOpFor`, the compare method's mask -> `compareOperator`,
  `WasmOperandTypes.withOperator`), and wasm's condition-position compare
  (`WasmComparisonCompiler.tryCompileConditionI32`, handed the test without `compileCons`).
- **A call-position rewrite reports under the operator it becomes** (`OperandTypes.REWRITTEN`: `1+`/`1-`
  -> `+`/`-`, `zerop`/`plusp`/`minusp`/`/=` -> `= > < =`, `evenp`/`oddp` -> `mod`, `logtest`/`logeqv`
  -> `logand`/`logxor`), because the compiled backends' function values (`#'1+`) ARE the rewrite
  (`BuiltinFunctionWrappers`), so the interpreter's built-in reports what a call does.
- `_int_val`'s limb-tier arm still TRAPS explicitly ([wasm-bignum.md](wasm-bignum.md)'s exact-or-trap
  boundary is about values that ARE integers). The `_as_f64` ladder is float-first
  ([wasm-shared-coercion.md](wasm-shared-coercion.md)). `--no-gc` unaffected, still traps.
- **What still traps on wasm-GC**: division by zero, the array argument of an access, the limb-tier
  boundaries -- and everything outside EH mode. (A list walk over a non-list is named since
  2026-09-26: "A wrong-type argument names its operator".)
- **The funnels' reach is wider than arithmetic**: a STORE into a packed float array goes through the
  same `_dbl`/`_as_f64`, and reports under `(SETF AREF)` since 972. Pinned by `JvmFloatArrayTest`'s
  `nonRealStoreIsATypeError`/`singleNonRealStoreIsATypeError` -- the reason to run the WHOLE suite
  after changing a shared runtime helper.
- Pre-existing edge unchanged: a condition thrown from INSIDE a wasm to-string capture leaves the
  capture flag set.

## A wrong-type argument names its operator
**Invariant: outside arithmetic too, a wrong-type argument reports `OP: The value <prin1> is not of
type T` with the type the operator requires, as a catchable `type-error` answering the datum and
`T`, byte-identical on all four backends (wasm-GC: in EH mode).** Covered since 2026-09-26:

| Form | Report |
| --- | --- |
| `(car 5)`, `(first 5)` / `(cdr "s")`, `(rest 5)` | `CAR: ... 5 ... LIST` / `CDR: ...` |
| `(nth nil l)`, `(nthcdr 1.5 l)` | `NTHCDR: ... INTEGER` |
| `(aref v nil)`, `(svref v nil)` | `AREF: ... INTEGER` |
| `(setf (aref v nil) x)`, `(setf (aref #d(1.0) 0) "x")` | `(SETF AREF): ... INTEGER` / `... REAL` |
| `(random nil)`, `(complex #c(1 2) 3)` | `RANDOM:` / `COMPLEX: ... REAL` |
| `(numerator nil)`, `(denominator 1.5)` | `NUMERATOR:` / `DENOMINATOR: ... RATIONAL` |
| `(lcm nil)`, `(gcd 1.5)` (one argument) | `LCM:` / `GCD: ... INTEGER` |
| `(random 1/2)`, `(random -1)`, `(random 0.0)` | `RANDOM: ... REAL` |
| `(nthcdr 1 5)`, `(nth 1 5)`, `(second 5)`, `(nthcdr 2 '(1 . 2))` | `NTHCDR: ... LIST` |
| `(endp 5)`, `(dolist (x 5))`, `(dolist (x '(1 2 . 3)))` | `ENDP: ... LIST` |
| `(char "ab" nil)`, `(schar "ab" 1.5)` | `CHAR:` / `SCHAR: ... INTEGER` |
| `(length 5)`, `(length 'foo)`, `(length (make-hash-table))` | `LENGTH: ... SEQUENCE` |
| `(last 5)`, `(mapcar #'1+ 5)` and `mapc`/`mapcan`/`maplist`/`mapl`/`mapcon` | `LAST:` / `MAPCAR: ... LIST` |
| `(rplaca 5 0)`, `(rplacd nil 0)`, `(setf (car 5) 0)` | `RPLACA:` / `RPLACD: ... CONS` |
| `(loop for x in 5 ...)`, `(loop for x in '(1 2 . 3) ...)` | `ENDP: ... LIST` |
| `(reverse 5)`, `(nreverse 5)`, `(reverse '(1 . 2))`, `(length '(1 . 2))` | `REVERSE:` / `NREVERSE:` / `LENGTH: ... SEQUENCE` |
| `(append 5 nil)`, `(append '(1 . 2) nil)`, `(list-length 5)` | `APPEND:` / `LIST-LENGTH: ... LIST` |
| `(member 1 5)`, `(member 9 '(1 . 2))`, `member-if`/`assoc`/`assoc-if`/`rassoc`/`rassoc-if` | `MEMBER: ... LIST` |
| `(mapcar #'1+ '(1 . 2))` and the other five, `(mapcan (lambda (x) 5) '(1))` | `MAPCAR:` / `MAPCAN: ... LIST` |
| `(char 5 0)`, `(schar 'foo 0)`, `(char (vector #\a) 0)` | `CHAR:` / `SCHAR: ... STRING` |
| `(setf (char s nil) c)`, `(setf (schar 5 0) c)` | `(SETF CHAR): ... INTEGER` / `(SETF SCHAR): ... STRING` |
| `(setf (char s 0) 5)`, `(setf (aref s 0) 5)` (`s` a string) | `(SETF CHAR):` / `(SETF AREF): ... CHARACTER` |
| `(row-major-aref v nil)`, `(setf (row-major-aref v nil) 0)` | `ROW-MAJOR-AREF:` / `(SETF ROW-MAJOR-AREF): ... INTEGER` |
| `(point-x 42)`, `(setf (point-x 42) 0)`, `(copy-point 42)` (a `defstruct`'s) | `POINT-X:` / `(SETF POINT-X):` / `COPY-POINT: ... POINT` -- generated code, not this table: [defstruct.md](defstruct.md) |
| `(copy-list 5)` | `COPY-LIST: ... LIST` |

- **`copy-list` of a non-list** (2026-09-26): used to signal a bare `type-error` whose
  `datum`/`expected-type` answered nothing on the interpreter (a raw
  `LispEvalException.ofClass`) and a `simple-error` on the compiled backends (a
  message-only `(error "The value ~s is not of type LIST" x)` inside
  `%copy-list-runtime`, kept instance-free by never naming a condition class). Now
  `COPY-LIST` is FUNNEL-TYPED like `last`/`append`/the rest of the list consumers: the
  interpreter's built-in goes through `Environment.requireListArgument`, and
  `%copy-list-runtime`'s non-list branch is `(%check-list x 'copy-list)` -- the same
  shared, instance-free funnel, so the fix costs nothing beyond one more table row.
- **FUNNEL-TYPED operators** (`OperandTypes.FUNNEL_TYPE`: `CAR`, `CDR`, `NTHCDR`, `ENDP`, `AREF`,
  `(SETF AREF)`, `CHAR`, `SCHAR`, `(SETF CHAR)`, `(SETF SCHAR)`, `ROW-MAJOR-AREF`,
  `(SETF ROW-MAJOR-AREF)`, `COPY-LIST`): each of their funnels checks ONE argument's type, so the funnel's kind IS the type
  (new kinds `LIST`, `RATIONAL`, `STRING`, `CHARACTER`) -- except that a to-double funnel (`NUMBER`) there is a packed float
  store, which takes any real: `REAL`. A numeric operator keeps its one fixed type. `%aset` reports
  as `(SETF AREF)`, `%row-major-aset` as `(SETF ROW-MAJOR-AREF)`, `nth` as `NTHCDR`, `svref` as `AREF`, `first`/`rest` as `CAR`/`CDR`
  (`OperandTypes.REWRITTEN`, the call-position-rewrite rule above). A one-argument `gcd`/`lcm`
  lowers to `(gcd x 0)`/`(lcm x 1)` (`LispMacroExpander.expandReduction`) -- it was `abs`, which
  accepted a float and named itself.
- **List walks** (2026-09-26): `nthcdr`'s walk signals on a non-list met before the count runs out
  (`(nthcdr 0 5)` is `5`, `(nthcdr 1 '(1 . 5))` is `5`); `nth` and `second`..`tenth` are
  `(car (nthcdr ...))`, so their report is `NTHCDR`'s or, on the last read, `CAR`'s. `endp` is
  checked (it was `(null x)`), and `dolist` checks ONCE, after its loop: the expansion is
  `(while (consp c) ...) (endp c) result` -- equivalent to CL's per-iteration `endp` (the body has
  seen 1 and 2 when `(1 2 . 3)` signals) with the loop unchanged. Interpreter: `endp` is a
  built-in function; `#'nth`/`#'second` name the failing step explicitly
  (`OperandTypeException.of(datum, kind, operator)`). JVM: `_nthcdr` throws `NTHCDR`'s report, and
  `endp` is `_endp` (nil or a cons answers itself, else `ENDP`'s report) plus the null test. wasm:
  `endp` is `WasmEmitHelper.emitListCheck` -- `ref.test $cons or ref.is_null`, else
  `_type_err_list` under the operator's id in EH mode (`emitListTypeError`) and a trap outside it
  (it traps there rather than answering nil); the `nthcdr` walk's step is the checked cons read
  itself, `br_on_cast_fail` with `emitListTypeError` as its miss, one type test per step
  ([cons-access-runtime.md](cons-access-runtime.md); 1M steps x100, wasmtime 49: 141 ms with a
  `ref.test` in front of the cast, 129 now, 131 unchecked), and keeps its trapping cast outside EH
  mode. The operator table adds `ENDP`
  for a program that spells `dolist`, `NTHCDR` and `CAR` for one that spells `nth`/`second`..
  (`WasmOperandTypes.LOWERED_TO`), and is placed in key order (it iterated `Map.of`s, whose
  order varies between JVMs). `char`/`schar` check the subscript as `aref` does (`_ckIdx`,
  `_idx_chk`).
- **List consumers** (2026-09-26; `length` answered 0, `last` NIL or its argument): `LENGTH`,
  `RPLACA`, `RPLACD` are FIXED-typed (`SEQUENCE`, `CONS`, two kinds no funnel produces -- the row
  names the type, so wasm reuses `_type_err_list`); `LAST` and the six `map*` are funnel-typed.
  A lowering checks through `(%check-list x 'op)` (`LispMacroExpander.checkListOf`): `last`'s
  binding, the `maplist`/`mapl`/`mapcon` guard (moved to the walk's end, next bullet), and
  `loop`'s `for-in` cursor under `ENDP` -- whose end test is `(if (consp c) nil (endp c))`, so `endp` runs only on the way out, as `dolist`'s
  does. Interpreter: `Environment.requireListArgument`. JVM: `_ckList` (nil or a cons) and
  `_ckCons` (returns the `Object[]`, replacing the site's `checkcast`) through the operator's
  wrapper; `%check-list` of `ENDP` calls `_endp`; `_length` throws `LENGTH`'s report for a symbol
  (a String without the quote) and any non-list. wasm: `emitListCheck` for the `map*` guards and
  `%check-list` (both modes; a trap outside EH), for `rplaca`/`rplacd` in EH mode only (outside
  it the cast still traps); `_seq_len` lands under `LENGTH`'s row, baked in like `_car`'s. The
  table gains `ENDP` for `LOOP`, `RPLACA`/`RPLACD` for `SETF INCF DECF PUSH POP PUSHNEW` (a
  `car` place's store). `#'rplaca`/`#'rplacd` became first-class on the compiled backends.
- **The rest of the list consumers** (2026-09-26; `reverse`/`member`/`assoc` answered NIL over a
  non-list, `append` a message-only error or the pad's generic text or a trap, `list-length` an
  instance-printing report, `length`/`mapcar` of a dotted list a count or a list): `REVERSE`,
  `NREVERSE` are fixed-typed `SEQUENCE`; `APPEND`, `LIST-LENGTH`, `MEMBER`, `MEMBER-IF`, `ASSOC`,
  `ASSOC-IF`, `RASSOC`, `RASSOC-IF` funnel-typed. **A walk is checked where it ENDS, not where it
  starts**: the atom it stops at must be nil, so one check covers a non-list argument (the walk
  ends at once) and a dotted tail (the datum is the tail: `LENGTH: The value 3 ...`), and no loop
  gains a test. The six scans end `((atom cur) (%check-list cur 'op))`
  (`LispMacroExpander.listScanEnd`; a literal proper list keeps its bare nil, so the internal
  `(member x '(...))` of `case`/`typecase` lowerings pays nothing); `reverse` is a consing `do`
  (it was `reduce` with a lambda) ending in the check; the user `nreverse` checks its cursor after
  the relink loop (`nreverseListForm(list, op)`; the internal reversals pass none); `list-length`'s
  prelude signals through `%check-list` over the atom it met. The `map*` walks check every cursor
  after the loop -- `maplist`/`mapl`/`mapcon`'s `do` result forms, the inline
  `Jvm/WasmMapcarCompiler`/`MapcCompiler`/`MapcanCompiler` after their exit (the JVM loop test is now `instanceof`, not
  `ifnull`), the interpreter's `mapFamilyValues` -- which replaced the up-front argument check; a
  `mapcan`/`mapcon` PIECE is checked as it arrives (the message-only `MAPCON: argument is not a
  list` is gone; a non-list LAST piece, which CL's `nconc` would answer, signals too). The
  multi-list value path (`BuiltinFunctionWrappers.mapFamilyWrapper`) checks each cursor as it is
  taken, so `(funcall #'mapcar f l 5)` reports `MAPCAR`, not `CAR`. Runtime helpers name
  themselves: JVM `_append` throws `APPEND`'s report (as `_length` does `LENGTH`'s) and `_length`
  tests the walk's end; wasm `_append` (EH mode only; a non-EH body is byte-identical) and
  `_seq_len` land under their rows. Interpreter: `Environment.requireListEnd`. Not covered:
  `member-if-not` & co. report under the `-if` operator their prelude defun calls; `nconc`, `reduce`
  and the other sequence functions are unchanged.
- **String accesses** (2026-09-26; a non-string was a message-only error, the pad's generic
  text or a trap, a symbol read as its NAME on the JVM): `char`/`schar` check the subscript,
  then the string -- that order on every backend, because the compiled sites check the
  subscript ahead of the read. A `setf` place names its STORE: `%schar-set`'s optional fourth
  operand is the quoted place head (`LispMacroExpander.scharSetOf`), reported as
  `(SETF CHAR)`/`(SETF SCHAR)`, as `(SETF AREF)` for an `aref`/`svref`/`elt` place's
  string arm (the array arm's name) and as `(SETF ROW-MAJOR-AREF)` for a `row-major-aref`
  place's. Interpreter: `charRef`, `scharSet(args, rebind, operator)`. Compiled:
  `expandScharSetFunctional` wraps the runtime defun's arguments in `(%check-string var 'op)`
  (a `char`/`schar` place only; the others run under `stringp`) and `(%check-index i 'op)`
  (`op` nil = unnamed) -- compile-path-only forms, since `%schar-set-runtime` cannot know the
  store's name. JVM: `_charRef` throws the unnamed `STRING` report for anything but a
  quote-framed String or a character vector, named by the operator's wrapper
  (`wrapForOperator`); `%check-string` is `_pStringp` plus a site throw
  (`_opTypeErr(_teRaw(x, "STRING"), op, FUNNEL_TYPE)`), `%check-index` is `_ckIdx` under `op`.
  wasm, EH mode only (a non-EH module is byte-identical): `_str_char_ref` lands a non-string
  through `_type_err(x, STRING)` directly (`WasmOperandTypes.emitLanding`; `STRING` has no
  `_type_err_*` stub) under the register its site sets (`emitCall`), `%check-string` is
  `emitStringpI32` plus `emitTypeError`, `%check-index` is `emitIndexCheck`. The landing
  selects `STRING` only when the table has a string-checking row (`STRING_CHECKED` adds its
  code to `rowCodes`); `LOWERED_TO` adds `(SETF CHAR)`/`(SETF SCHAR)` for `CHAR`/`SCHAR` and
  `(SETF AREF)` for `ELT`.
- **String stores and `row-major-aref`** (2026-09-26; a non-character store was
  `%SCHAR-SET`'s message-only error, a silent JVM store that later escaped as a
  `ClassCastException`, a wasm cast trap; a `row-major-aref` subscript unnamed, a
  `NullPointerException` or a trap): a `%schar-set` checks string, subscript, VALUE, then
  bounds -- the value is `CHARACTER`, a new kind. Compiled: the runtime defun's third
  argument is `(%check-character c 'op)`, every place (`op` nil = unnamed). JVM:
  `instanceof int[]` plus the site throw `%check-string` uses (`emitSiteTypeError`). wasm, EH
  mode only: `ref.test $char` plus `emitTypeError`; the landing selects `CHARACTER` only when
  the module carries `%schar-set-runtime` (`WasmOperandTypes.CHARACTER_CHECKED`, the one
  function every string store calls). The interpreter's `%aset`/`%row-major-aset` string arm
  (`storeStringChar`) throws the unnamed `CHARACTER` report the seam names. `row-major-aref`
  and `%row-major-aset` check their subscript as `aref`/`%aset` do (`compileSubscript`: JVM
  `_ckIdx`, wasm `_idx_chk` on every arm), and the JVM store goes through the wrapper, so a
  packed float store's non-real reports `(SETF ROW-MAJOR-AREF): ... REAL` as the
  interpreter's seam now names it. `LOWERED_TO` adds `(SETF ROW-MAJOR-AREF)` for
  `ROW-MAJOR-AREF`.
- **Interpreter**: the built-ins throw `OperandTypeException` with the kind (`car`/`cdr`/`first`/
  `rest` and `nthValue` `LIST`, `numerator`/`denominator` `RATIONAL`, `random` `NUMBER`/`REAL`), the
  seam names them. `#'first`/`#'rest` of nil and `#'second` past the end answer nil now, as the
  calls do (they signalled "expects a cons cell").
- **JVM**: `car`/`cdr` compile to `_car`/`_cdr` (`JvmOperandTypeRuntime`), whole readers that name
  themselves (`_opTypeErr(_teRaw(x, "LIST"), "CAR", FUNNEL_TYPE)`) -- one call per site where the
  inline null test and cast were; `zlib` -4.5 KB, a one-defun class +227 B (the two methods). The
  subscripts of `aref`/`%aset`/`nthcdr` go through `_ckIdx` (a `Long` or `BigInteger`), `numerator`/
  `denominator` through `_ckRat`, both via the operator's wrapper; `%aset`'s store helpers and the
  `complex` constructor are invoked under the wrapper too. `_opTypeErr`'s funnel-typed arm reads the
  kind back off the raw report.
- **What is a cons on the JVM** (`JvmOperandTypeRuntime.ConsShape`): `_car`/`_cdr`/`_endp`/
  `_ckList`/`_ckCons`, `_nthcdr`'s walk and the inline `mapcar`/`mapc`/`mapcan` walks (`_isCons`)
  exclude the other `Object[]`s exactly as `consp` does: a ratio, a function reference (`Integer`
  head), an instance (`String[]` head; tested only when `mayUseInstances`) and an async value
  (`Object[3]` + marker; only with the async runtime). Until 2026-09-26 they tested `instanceof
  Object[]` alone: `(car an-instance)` answered its layout, `rplacd` overwrote its first slot, and
  `(car c)` in a `handler-bind` handler returned. Cost, measured 2026-09-26 (JDK 25, 10 interleaved
  runs pinned to 4 cores on a loaded host, best/median ms): 1M-element `car`+`cdr` walk x40
  156/189 -> 161/190; `endp`+`cdr` walk 182/242 -> 150/187; `dolist` 159/191 -> 161/188; `mapcar`
  over 10k x2000 131/150 -> 132/150; bench-report `list` 463/513 -> 463/484. A `mapcar` class
  +165 B. Pins: `JvmLispCompilerTest.consAccessorsRejectEveryObjectArrayThatIsNoCons`, ci-spec
  `cons-accessors-reject-object-arrays-that-are-no-cons` and `failing-handler-bind-handler-report`.
- **wasm-GC, EH mode only** (`WasmEmitHelper.checksConsFields`; outside it every cast still traps and
  a non-EH module is byte-identical): an inline `car`/`cdr` site is ONE type test over the operand
  on the stack, `block block br_on_cast_fail 0 eqref (ref $cons); struct.get; br 1 end; call _car
  end` ([cons-access-runtime.md](cons-access-runtime.md)), and `_car`/`_cdr` are CHECKED there -- nil answers nil, a non-list sets the register to `CAR`'s/`CDR`'s
  row and lands in `_type_err_list`; a `--optimize=size` site was already that call and pays nothing.
  The body names itself whichever form reached it (an unnamed report when the program spells neither
  name). A subscript goes through `i32.const id; ref.i31; call _idx_chk` (+6 B): a fixnum answers
  itself, anything else `_int_val` under the id. `compileAset` names itself (a statement-position
  store and a pinned-kind `setf` reach it without `compileCons`). `random`'s integer arm first runs
  the limit through `_as_f64` (a ratio passes and meets `_int_val`'s unnamed, true, `INTEGER`);
  `denominator` checks a non-ratio through `_int_val`, since `_rat_den` answers 1 for anything.
- **Cost, measured 2026-09-26** (wasmtime 47): P1 `zlib` 114,383 -> 115,984 (+1.4%), size level
  87,936 -> 88,735 (+0.9%); a tight 1M-element `car`/`cdr` loop in an EH module 129 -> 166 ms
  (+28%), while the site tested the type twice (`ref.test`, `ref.cast`). With the one
  `br_on_cast_fail` (same day, wasmtime 49) that loop is 113 -> 92 ms against 84 unchecked, and
  most list walks are at or under the unchecked time (the table:
  [cons-access-runtime.md](cons-access-runtime.md)). The `aref` loop and the JVM are unchanged.
- **Cost of the list walks, measured 2026-09-26**: `zlib` P1 115,984 -> 116,300 (+0.27%), size level
  88,735 -> 89,051, JVM classes 163,399 -> 163,693; `hello_world`, `pi_approx`, `dom_reactor`
  unchanged. No loop gains a test.
- **Cost of the list consumers, measured 2026-09-26**: `zlib` P1 116,527 -> 116,892 (+0.31%), size
  level 89,272 -> 89,623, JVM class 164,202 -> 164,583; `hello_world`, `pi_approx`, `dom_reactor`
  unchanged. A 1M-element `loop`/`dolist`+`rplacd`/`length` mix in an EH module 0.34 -> 0.36 s
  (the `rplacd` site's `ref.test` in front of its cast: not the one-test `br_on_cast_fail` of a
  `car`/`cdr` read, whose block would need a cast-typed signature); the JVM unchanged.
- **Cost of the string accesses, measured 2026-09-26** (wasmtime 49): `zlib` code +201 B and
  strings +52 B; `hello_world`, `pi_approx`, `dom_reactor` unchanged, a non-EH module
  byte-identical, JVM class 164,583 -> 164,736. Its P1 total read 117,008 -> 118,253 only because
  the 52-byte shift put the fdlibm table's then-probed base word on chipz's literal `2048`, pinning
  ~990 dead bytes; with the table decided by its readers it is 117,260
  (`.kb/optimize-dead-code-elimination.md`). A 21M-read `char` loop: JVM unchanged (noise), EH wasm 560 ->
  580 ms (the register write around `_str_char_ref` and its quote-frame test).
- **Cost of the rest, measured 2026-09-26** (on the tree before the string accesses): `zlib` P1 117,008 -> 116,675, size level 89,623 ->
  89,254, JVM class 164,583 -> 161,636 (`reverse`'s `do` replaces a `reduce` over a lambda);
  `hello_world`, `pi_approx` unchanged. The price is paid by a TINY EH module: one whose only
  checked site is a `length` or an `append` (`(handler-case (length *x*) ...)`, 1,573 -> 6,127 B)
  now keeps the landing and its tables, because the dotted-tail test at the walk's end is one the
  whole-module type facts cannot prove away; `examples/console/error-handling.lisp` 35,901 -> 35,641.
- **Cost of the string stores and `row-major-aref`, measured 2026-09-26** (wasmtime 49, on the
  tree after `.todo/985` and `990`): `zlib` P1 116,920 -> 117,028 (code +67 B, the landing's
  `CHARACTER` arm +16; data +41 B, the `CHARACTER` suffix and the new row); size level 89,499 ->
  89,051 (code -488 B: `WasmRefTypeFolder` proves `%schar-set-runtime`'s value a character, so its
  packed-integer arm folds to a trap and one body folds away); JVM class 172,368 -> 173,351
  (+983 B: the `_ckIdx`/store wrappers and chipz's `row-major-aref`/`fill`/`replace` sites).
  `hello_world`, `pi_approx`, `dom_reactor` byte-identical; a non-EH module too. A 20M-iteration
  `row-major-aref` read+store loop: EH wasm 0.91 -> 1.08 s, what the same `aref` loop already
  paid (0.99 s); JVM unchanged (1.67 -> 1.65 s at 300M).
- Pinned by `ci-spec.yaml`'s `argument-type-errors-name-the-operator-beyond-arithmetic` and
  `list-walks-and-string-indices-name-the-operator`, `list-consumers-name-the-operator`,
  `list-consumers-beyond-the-first-set-name-the-operator`, `string-accesses-name-the-operator` and
  `string-stores-and-row-major-subscripts-name-the-operator`, and the
  `argumentTypeErrorsNameTheOperatorBeyondArithmetic` /
  `listWalksAndStringIndicesNameTheOperator` / `listConsumersNameTheOperator` /
  `listConsumersBeyondTheFirstSetNameTheOperator` / `stringAccessesNameTheOperator` /
  `stringStoresAndRowMajorSubscriptsNameTheOperator` triples
  (`LispEvaluatorTest`, `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`).

### `random`'s domain (closed 2026-09-26, `.todo/981`)
CLHS's domain is a COMPOUND type, `(OR (INTEGER 1) (FLOAT (0.0)))`: a ratio limit is real but
neither integer nor float, and an integer or float limit `<= 0` is out of range either way. No
single existing `OperandTypes.Kind` names that domain truthfully, and `WasmOperandTypes.Texts`
interns one SYMBOL per `Kind`, not a compound cons structure, so building the CLHS-precise type
would mean teaching the shared operand-type table (used by many operators) a new representation
for this one operator alone. Resolution: report it under RANDOM's own ALREADY-registered type,
`REAL` -- the same text `(random nil)` already got before this fix -- rather than adding
compound-type machinery only `random` would ever use. A known simplification (like "no
random-state objects exist" above), not a claim that a ratio or a negative number is not real.

- **Interpreter** (`Environment.java`'s `RANDOM` builtin): the three hand-written
  `LispEvalException` throws (ratio, non-positive int/float/bignum) became
  `OperandTypeException.of(limit, Kind.REAL).named(RANDOM)`, the exact call the pre-existing
  non-real case already made -- catchable now, where they were plain uncatchable-by-class
  `simple-error`s before.
- **JVM, the general path** (`JvmNumericRuntimeBuilder.buildRandom`, `_random`): a ratio limit
  (`BigInteger[]`) and a `<= 0` limit (checked via `_dbl`, so one comparison catches Long, Double
  and BigInteger alike) both throw the unnamed `_teRaw(limit, REAL)` `_dbl` already throws for a
  non-real limit; the per-(helper, operator) wrapper around every `_random` call site (already
  established for the non-real case) renames it the same way.
- **JVM, the two paths that bypass `_random` for performance** -- `.kb/random.md`'s "Four JVM
  sites must agree on the FORMULA" already tracked this exposure:
  - `JvmRandomCompiler`'s double-literal fast path (`unboxDouble` + inline multiply, no dispatch)
    now checks the limit's sign first: positive re-runs `unboxDouble` (a pure coercion, cheap to
    call twice) and inlines as before; non-positive calls the wrapped `_random` on the STILL-BOXED
    value instead of drawing, which throws before any draw happens (no double-draw).
  - `JvmIntFusionCompiler`: a foldable constant limit `<= 0` no longer builds a `RandomLeaf` at
    all (`randomLeaf` returns null, the same bail a ratio/bignum limit already took), forcing the
    call through the checked unfused path. A runtime Long limit `<= 0` now joins the "not a Long"
    trampoline in `emitRandomDraw` (which already calls the wrapped `_random`) instead of drawing.
    That trampoline's `ctx.numOp(RANDOM)` call needed an explicit `ctx.operator = RANDOM` around
    it: the fusion planner reaches a `(random ...)` argument STRUCTURALLY, never through
    `JvmExprCompiler.compileCons`'s per-form dispatch that normally sets `ctx.operator`, so the
    wrapper was resolving unnamed until this was added.
- **wasm-GC, EH mode only** (gated behind `WasmEmitHelper.checksConsFields`, like the non-real
  check above -- a non-EH module is unchanged, byte for byte): the float branch (both the
  literal-argument fast path and the runtime `ref.test TYPE_FLOAT` one) checks the limit's `f64`
  value against `0.0` before scaling; the integer branch's `_int_val` call -- which already
  rejected a ratio, since a ratio is neither an i31 nor a boxed integer, but UNNAMED (a raw `call`,
  not `WasmOperandTypes.emitCall`) -- now goes through `emitCall` like `_as_f64` beside it, so it
  reports RANDOM's own `REAL` under the operator register instead of an unnamed `INTEGER`; the
  extracted `i64` limit is then checked `<= 0` before the unsigned remainder (which previously
  read a negative limit's two's-complement bit pattern as a huge unsigned value). `_int_val` is
  pure, so calling it a second time for the actual remainder draws nothing extra.
- Pinned by `ci-spec.yaml`'s `random-limit-domain-violations-signal-a-type-error` and the
  `randomLimitDomainViolationsSignalATypeError` / `ehRandomLimitDomainViolationsSignalATypeError`
  triple (`LispEvaluatorTest`, `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`).

## An out-of-range subscript is a type-error naming its bound
**Invariant: a subscript outside its dimension reports `OP: The value S is not of type (INTEGER 0
(D))` -- CL's `type-error` for an array index, SBCL's `invalid-array-index-error` -- as a catchable
`type-error` whose `type-error-datum` is the subscript and whose `type-error-expected-type` is the
LIST `(INTEGER 0 (D))`, byte-identical on all four backends (wasm-GC: in EH mode).** Closed
2026-09-26 (`.todo/a00`); it was `aref: index out of bounds` interpreted, the host's `Index 5 out of
bounds for length 5` (the length counting header slots) uncaught on the JVM and an uncatchable trap
on wasm-GC even in EH mode.

- **Per axis.** Each subscript is checked against ITS OWN dimension, the first failing axis
  reporting (`D` is that axis's dimension): until then only the row-major total was checked, so
  `(aref m 0 2)` on a 2x2 array answered `m[1][0]` on all four. A row-major access
  (`row-major-aref`, rank 0) is bounded by the total size. A bignum subscript is out of range, not a
  wrong-type one.
- **Names**: `AREF` (also `svref`, and `elt` of a vector, which expands to `aref`), `(SETF AREF)`,
  `ROW-MAJOR-AREF`, `(SETF ROW-MAJOR-AREF)` -- the operator the wrong-type subscript already
  reported under ("A wrong-type argument names its operator"). `OperandTypes.indexType` holds the
  text.
- **Order**: every subscript's type, then a store's value (its evaluation and a packed store's
  coercion), then the bounds -- what the interpreter's `subscriptValues` -> `asDouble` -> `inBounds`
  does and every compiled store now does: `(setf (aref dv 5) "x")` on a 3-element `double-float`
  vector reports `REAL`, and a value form's side effects run before an out-of-range store fails.
- **Interpreter**: `Environment.subscriptValue` / `inBounds` / `boundedSubscripts` ahead of each
  representation's accessor; `OperandTypeException.outOfRange` is named by the built-in seam like a
  wrong-type operand and `expectedType()` builds the list. A string's `aref`, `char`, `schar` and
  their stores report the same text (`charRef`, `scharSet`, `storeStringChar`) -- the decision for
  `.todo/186`, whose compiled string paths are still unchecked.
- **JVM** (`JvmOperandTypeRuntime`): `_oob(datum, dim)` builds the unnamed report and records the
  list under a pad; `_ckBound(Object, int)` checks a boxed subscript, `_ckBoundJ(long, int)` a raw
  one through `Objects.checkIndex`, the JIT's range-check intrinsic. Every accessor bounds its
  subscripts through them -- `_aref1`/`_aset1` against the total (`emitFlatBound`: a packed array's
  `long[]` length, a boxed one's `ArrayList` size, a displaced view's own dims product),
  `_aref2`/`_arefN` and the stores per axis, the `_fv*` widths against `d.length - off` and the
  header dims, `_ivAref1`/`_ivAset1`, the quantized `_qmAref*` -- and reads now go through the
  operator's wrapper as stores did. `_opTypeErr` finds the type after `TYPE_INFIX`, not after the
  last space, and keeps a compound type (and the record's list) verbatim under any operator. The
  typed loops (`.kb/jvm-typed-loops.md`) check each subscript with `_ckBoundJ` under the access's
  wrapper; the int-fusion aref leaf bails on a long index past the int range and its fallback runs
  under `AREF`'s wrapper.
- **wasm-GC, EH mode** where the operator table names an access (`Operators.indexed`, which also
  interns the two texts and gives `_type_err` its index arm: kind `-1 - bound`, the type built as
  the list): a read's subscript goes through `_idx_ref(array, subscript, op)` INSTEAD of `_idx_chk`
  -- the integer check and the flat bound by representation in one call; a store checks after its
  value through `_idx_ref`, or, at the speed levels, an inline compare against the arm's own bound
  (a packed integer vector's length, dimension 0) that calls it only on a miss; a pinned-kind
  read at the speed levels does the same after `_idx_chk`. A packed integer store's raw value waits
  in an i64 temp. `_idx_chk` answers a limb-tier integer rather than trapping in `_int_val`.
- **wasm-GC, every mode**: a rank-2+ access checks each axis (`_idx_in`, a trap outside EH mode --
  the wrap-around above was a wrong answer there); a `--simd` block's zero padding past its count is
  checked (`_idx_bound`), where no engine check reaches. **A rank-1 access outside EH mode keeps the
  engine's own trap and its module byte for byte**, except that a displaced view's out-of-range
  read still reaches through to its target there.
- **Cost, measured 2026-09-26** (wasmtime 49, JDK 25): zlib P1 124,519 -> 127,370 B (+2.3%), size
  level 95,803 -> 96,729, component 128,550 -> 131,399 -- EH mode (chipz's `unwind-protect`) and
  every access checked; `hello_world`, `pi_approx`, `dom_reactor` and a non-EH rank-1 array program
  byte-identical. JVM class: zlib 185,749 -> 186,421, pi_approx 14,495 -> 14,576 (the
  compound arm of `_opTypeErr` every wrapped program carries). Run time: chipz inflating 20 KB x300 in an EH
  module 782 -> 829 ms (+6%); EH micro loops over 1000 elements, 400M accesses: `u8` read 425 -> 503
  ms, `u8` write 985 -> 1172, a declared `double-float` read+write loop 5.1 -> 6.6 s, a general
  vector 962 -> 1137, a rank-2 packed read 293 -> 368. JVM (4G accesses): typed `double-float` loop
  2624 -> 2654 ms, packed `u8` 2704 -> 2730, general vector read 1148 -> 1131, write 1111 -> 1215,
  rank-2 packed 1142 -> 1127. **A hand-written compare in the typed loop cost it 27%**, and
  `Objects.checkIndex` there 1.7%; the same intrinsic in the BOXED `_ckBound` made a general-vector
  store 4x slower (the extra inline depth), so the boxed check stays a compare.
- Pinned by `ci-spec.yaml`'s `out-of-range-subscripts-are-type-errors-naming-their-bound` and the
  `standalone:` `uncaught-out-of-range-subscript-report`, and the
  `outOfRangeSubscriptsAreTypeErrorsNamingTheirBound` triple (`LispEvaluatorTest`,
  `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`).

## Argument-shape errors signal a catchable program-error
**Invariant: a keyword the operator does not accept, an odd keyword tail and a non-keyword in
keyword position signal a CATCHABLE `program-error` carrying one text -- `REMOVE expects keyword
arguments :TEST/:TEST-NOT/:KEY/:START/:END/:COUNT/:FROM-END, got: :BOGUS` -- on all four backends,
from a call form and from a
first-class call alike, and `:allow-other-keys` suppresses the check per CLHS 3.4.1.4.1.1.** They
used to leave the expander as `IllegalArgumentException`s no `handler-case` could see (the ANSI
report's two top rows: 370 + 299 lost forms) and to fail the COMPILE on the compiled backends.

- **One validator, `LispMacroExpander.keywordTailProblem(name, parts, start, allowed)`**: the
  complaint or null, over argument FORMS (the expansion-time check behind `keywordTailError` /
  `testKeyKeywordTailError`, 27 operators) and over argument VALUES (the interpreter's first-class
  validators, `LispEvaluator.requireKeywordTail`, and `Environment`'s `make-string-output-stream`),
  so the two cannot disagree on the rule or the text. The LEFTMOST `:allow-other-keys` pair decides;
  a value that is not a literal nil counts as true (a computed one suppresses statically -- the
  lenient direction); the key itself is always accepted; an odd tail is malformed whatever the
  suppression says. Every message spells the allowed set upcased and `/`-joined.
- **The expander RETURNS the signal instead of throwing.** `programErrorForm(call, text)` is
  `(%program-error "text")`, `SourceProvenance.inherit`ing the call's position, and the rejected
  call EXPANDS to it (the keyword checks and the eleven `X expects N arguments, got M` arity checks;
  `LambdaLists.unknownKeyCheck`'s unknown-`&key` signal is the same primitive over a runtime-built
  message). Interpreter: `evalConsRareOperator` throws
  `LispEvalException.ofClass(PROGRAM_ERROR_CLASS_NAME, text)` -- class-named, the instance synthesized
  at the catch like a bad `car`'s. Compiled: `lowerProgramError` emits
  `(%error-cond (%obj-new '%class-PROGRAM-ERROR ... text) text)` behind a handler landing pad
  (`Ctx.hasLandingPad` = `establishesLandingPad(program)`, an operator-position scan for the
  `LANDING_PAD_HEADS`, which implies the instance gate) and a plain `(%error text)` otherwise --
  nothing can observe the class without a pad and the top-level line is identical either way. Both
  compilers first call `CompileWarnings.warnStaticProgramError`: `warning: <text>; compiled as a
  call-time program-error` at the call's position (the undefined-function precedent), for a LITERAL
  message only. `PROGRAM-ERROR` is seeded with `format-control`/`format-arguments` (the fifth
  reporting class) so the instance reports its text through the ordinary `simple-condition` report.
- **Three gates stand in for the construction, which happens during BODY compilation where no scan
  sees it**: `conditionNarrowing` marks `%class-PROGRAM-ERROR` constructible behind a pad, beside the
  raw-failure classes; `WasmLispCompiler.usedLayoutTags` bakes the layout on the same predicate; and
  `WasmErrorCompiler.compileCond` compiles the message operand when the program has NO report
  renderer (`routesConditionReports` off -- every source-visible `%error-cond` producer flips routing
  on, so only this lowering can reach it), because the entry landing pad's report of the instance can
  then come only from the payload cdr. Without the third, `(print (handler-case (error "warm") (error
  (e) :ok))) (remove 1 '(1 2) :bogus 4)` printed `Unhandled condition: ` and nothing else.
- **The interpreter's evaluation seam**, the catch clauses of `LispEvaluator.evalCons`'s loop frame
  (`.kb/interpreter-tail-calls.md`): an `IllegalArgumentException` / `IndexOutOfBoundsException` escaping a form is a
  `program-error` (that is how the expander and the special forms report a malformed form), a cast /
  arithmetic / negative-size failure takes `rawFailureConditionClass`'s rule, and an
  `UnsupportedOperationException` -- a rontolisp LIMITATION (`setf does not support place`, `map
  supports only the 'list ...`) -- stays raw, since catching a limitation would let a program run on
  past what this implementation cannot do. So the long tail of per-site rejections that are NOT
  lowered (`setf: odd number of arguments`, `make-sequence: unsupported result type`) is catchable
  interpreted and a compile-time `error:` compiled -- the CL-conformant asymmetry: a compiler may
  reject at compile time what the evaluator signals at run time. `handler-bind` handlers for these
  run at the pad, not at the signal point (the compiled-backend semantics).
- **Arity**: a surplus argument past an `&optional` tail is `Function expects at most N ...`, a
  check the lambda-list desugaring emits on every backend ([lambda-lists.md](lambda-lists.md)).
  The interpreter's `Function expects N argument(s), got M` (lambda application), `Macro X
  expects ...`, `Environment.requireArgCount*` and every inline `X expects N arguments, got M` built-in
  check are `program-error`s (the ANSI suite's next six rows). `requireArgCount` /
  `requireMinArgCount` spell theirs through `ClosRegistry.arityMessage(name, ...)` -- so `1
  argument`, not the `1 arguments` they wrote until 2026-09-26. The compiled backends signal the same
  through a function VALUE ("A wrong argument COUNT" below), `apply` included since 2026-09-12.
  `-`/`/` (no identity, unlike `+`/`*`: `compiler/ArithmeticIdentities`) inline-check their own empty
  argument list in `Environment.registerArithmetic` rather than fall through to the generic
  `IndexOutOfBoundsException` conversion above, so `(-)`/`(/)` signal the SAME text interpreted as
  `ArithmeticIdentities.of` rejects at compile time, instead of the interpreter's former raw `Index 0
  out of bounds for length 0`. Since 2026-09-26 that text is the count report, `- expects at least 1
  argument, got 0` (it was `- requires at least one argument`), which is also what `(funcall #'-)`
  says compiled: the `-`/`/`/`min`/`max` wrappers take their first argument as a required `n`
  (`(n &rest r)`), where a bare `&rest` folded over nil into a type-error or answered nil.
  The first-class twins are pinned on
  `#'member` / `#'find` / `#'position` and, since the family took the bounding keywords, on
  `#'remove` too -- its wrapper now forwards a keyword tail instead of taking a fixed two arguments
  ([sequence-bounding-keywords.md](sequence-bounding-keywords.md)).
- ANSI `sequences` chapter, interpreter, 2026-09-08 (`ansi-test/measure.sh sequences`): 1,850 /
  2,454 pass with 849 forms lost before; 2,191 / 3,274 pass with 29 lost after. The
  `X expects keyword arguments ...` rows that remained (350 + 228) were counted test ERRORS naming
  real gaps -- `:count`/`:start`/`:end`/`:from-end` on the substitute / remove family, `:from-end`
  on `count` -- rather than forms the driver could not evaluate. Those gaps are closed
  ([sequence-bounding-keywords.md](sequence-bounding-keywords.md), 2026-09-11): 2,861 / 3,287 pass,
  166 fail, 265 error -- +657 tests, no test that passed before failing after.

## A wrong argument COUNT through a function value
**Invariant: calling a function VALUE with a count its lambda list cannot take signals a catchable
`program-error` on every backend, spelled by the ONE `ClosRegistry.arityMessage`** -- `Function
expects [at least ]N argument(s), got M`, with the OPERATOR in place of `Function` when the callee
is a built-in's (`CONS expects 2 arguments, got 1`; "Naming the operator" below). Only a DIRECT
call is checked at compile time; everything else (`funcall`, `mapcar`, `sort`, a bare `(f x)` whose
head is an expression) arrives at an `_invoke_N` dispatcher, whose no-match arm used to answer nil
on the JVM and `unreachable` on wasm-GC. A silent nil is the worst of the three: an ANSI
`signals-error ... program-error` row passed interpreted and returned a WRONG VALUE compiled.
Pinned by ci-spec `wrong-arity-funcall-signals-program-error` and `JvmLispCompilerTest`
`compileAndRunWrongArityThroughAFunctionValueSignalsProgramError` /
`...ThroughABuiltinDesignatorNamesTheOperator` (and its wasm and interpreter twins).

- **JVM** (`JvmRuntimeBuilder.ArityReporting`): the arm calls `_arityErr(funcId, got)`, which reads
  the callee's SHAPE (required count doubled, plus one for a `&rest` tail) out of a STRING indexed
  by funcId and throws `new WrongMethodTypeException(_arityMsg(shape, got))`. A search tree over the
  dispatchable ids -- the shape the dispatchers themselves use -- is the wrong structure here: ~15
  bytes per callable in ONE method, and the cl-postgres corpus overflowed the signed 16-bit branch
  offset on it. The table is one byte per funcId and three instructions, whatever the program's
  size. `JvmHandlerCaseCompiler.emitRawFailureTest` recovers `program-error` from the exception's
  CLASS (`JvmRuntimeBuilder.ARITY_EXCEPTION_CLASS`, the sixth entry in
  `LispMacroExpander.rawFailureConditionClasses()`). It used to recover it from the `Function
  expects ` prefix of the TEXT (the unbound-variable precedent), which a named report does not
  start with -- and which a user's `(error "Function expects ...")`, a plain `RuntimeException`,
  matched too: that user error was a `program-error` on the JVM alone until 2026-09-26. The one
  visible cost: an UNCAUGHT report's JVM stderr line names `java.lang.invoke.WrongMethodTypeException`
  where it said `java.lang.RuntimeException` (the `Unhandled condition:` line is unchanged).
- **wasm-GC** (`WasmRuntimeBuilder.ArityReport`): the `br_table` already has a label per funcId, so
  the ids this arity cannot serve point at one ARM PER SHAPE instead of at the default; the arm puts
  the shape in the (now dead) funcId local and branches to one assembly block per dispatcher, which
  builds the message from five interned pieces -- the two counts rendered by `_prin1_to_str` over an
  `i31`, as the interpreter prints them -- constructs the `program-error` instance the way
  `%obj-new` does and throws the `(instance . message)` payload on `$lisp-cond`. EH mode behind a
  handler landing pad only; outside it the arm is the `unreachable` it was, byte for byte. The
  pieces are interned on FIRST USE: eagerly interning them costs a module whose dispatchers all turn
  out to be dead an extra data-segment header.
- **`apply` is covered by a COUNT GUARD, not by a dispatch miss** (2026-09-12). Neither of its two
  shapes can miss: a literal `(apply #'f ... list)` compiles to a physical direct call that reaches
  no dispatcher (`Jvm/WasmApplyCompiler`), and a computed designator reaches the SPREAD dispatcher,
  which carries a case for every callable and reads the parameters out of the list with car/cdr --
  both answer nil past its end, so a short list BINDS nil and a long one drops its tail. Both sites
  therefore carry one shared helper that measures the LIST against the callee shape baked at the
  site: `_arityChk(argList, shape)` on the JVM (`JvmRuntimeBuilder.buildArityChkBody`, ~6 B per
  site) and `_arity_chk(argList, shape) -> i32` on wasm-GC
  (`WasmRuntimeBuilder.buildArityChkBody`, 7 B per site including the `drop`). The wasm helper is a
  CONDITIONAL function index (`WasmLispCompiler.arityChkFuncIndex`, right after the extra per-arity
  dispatchers) decided in the pre-pass, because it shifts `userFuncBase()`; a module without it is
  byte-identical. Inlining the throw instead would have cost ~100 B at every one of those sites,
  over a spread dispatcher that is one case per callable.
  - The walk **stops at the first non-cons**, so an improper tail ends the count rather than
    trapping on the `ref.cast` / `checkcast`: `(apply #'f '(1 . 2))` is undefined in CL and
    answered `(f 1)` before the guard existed.
  - The guard is why this half waited: what it found FIRST was not a user bug but a compile-path
    leak, a failing cl-ppcre scan leaving `*reg-starts*` bound past the special `let` that shadowed
    it, the phantom register arriving as a second argument to a one-parameter `:simple-calls`
    replacement. That leak is closed (`.todo/192`, the every-exit restore in
    [dynamic-special-variables.md](dynamic-special-variables.md)) and the guard is green over the
    real cl-ppcre corpus on both compiled backends.
  - `WasmAsyncEmit.freshCtx` must forward `arityChkFuncIndex`: it builds the SYNCHRONOUS top level
    too, so dropping it leaves a top-level literal `apply` unguarded while the same form inside a
    defun reports -- the same trap `callArityCeiling` and `extraDispatchFuncBase` are listed there
    for.
- **Naming the operator** (2026-09-26). The interpreter's Java built-ins always named themselves
  (`requireArgCount(name, ...)`); a function value that is a `BuiltinFunctionWrappers` lambda said
  `Function` -- on the compiled backends for every built-in, in the interpreter for the ones it
  resolves through `lambdaFor`. ONE rule now decides on all four:
  `BuiltinFunctionWrappers.arityOperator(name)` -- the callee's name when it is a catalog name, else
  `Function`. By NAME, because the interpreter's catalog lambda and the compilers' injected wrapper
  defun are the same function under it (a user `defun` of a catalog name is named too, on every
  backend alike). A program's own functions keep `Function`: naming them would also have to name
  the `&optional` surplus and destructuring checks the lambda-list desugaring emits without a name
  ([lambda-lists.md](lambda-lists.md)), or one function would report under two names.
  - **Interpreter**: `resolveFunction` gives the catalog lambda its name (so `#'elt` prints
    `#<function ELT>`, as it always did compiled), and `checkArity` asks `arityOperator` of it.
  - **JVM** (`JvmArityOperators`): the shape carries the operator's 1-based index from bit 16 up.
    Every site that bakes a shape registers through it -- a literal `apply`'s guard, a spread case,
    the `_arityErr` table (whose cell keeps `shape + 1` in the low seven bits and the index in the
    nine above, so an unnamed cell stays one byte) -- and `_arityMsg` reads the name back out of one
    newline-joined string constant (`split` on a one-char non-regex pattern). `buildArityMethods`
    registers every dispatchable callee before freezing the registry for `_arityMsg`, which covers
    the spread cases built after it; a late registration throws. A named spread case's shape is past
    `sipush` range and is `ldc`'d. `_arityMsg` and `_arityChk` mask the operator bits off before
    reading the required count -- `_arityChk` without the mask rejected every RIGHT-count `apply` of
    a built-in (`(apply #'cons '(1 2))`), which the wrong-count tests alone could not see.
  - **wasm-GC**: the shape carries `funcId + 1` from bit 16 up (`WasmRuntimeBuilder.arityShape`),
    and one shared function, `_arity_opening(shape, _) -> string` (TYPE_RAT_NEW, a conditional
    index right after `_arity_chk`, reserved once the defuns are final:
    `WasmLispCompiler.emitsArityOpening`), selects the operator by funcId with the dispatchers' own
    `emitCaseSelector` and concatenates one shared `" expects "`. A dispatcher whose misses include
    a named callee writes `(funcId + 1) << 16 | shape` from its arm (the page bias folded into the
    constant); `_arity_chk` and the spread cases read it off the shape they are handed. The named
    set is the catalog defuns that are DISPATCHABLE or the callee of a guarded literal `apply`
    (`Ctx.arityNamedCallees`, forwarded by `WasmAsyncEmit.freshCtx` with `namesArityOperators`):
    wasm injects every wrapper and shakes most, so naming them all kept every operator's piece in
    every module. The first cut inlined the selection into each dispatcher and cost the eval
    module below +36 KB -- one copy per dispatcher arity.
  - Still divergent, in the EXPECTATION half only: a built-in the interpreter implements in Java
    with an optional tail spells its own range (`GETHASH expects 2 or 3 arguments, got 0`), while
    the compiled wrapper's lambda list says `at least 2`; and a wrapper's `&optional` surplus check
    says `Function expects at most N`.
- **Inside a compiled `eval`** (2026-09-26) the same reports hold: the runtime evaluates every
  argument form of a registered function and the spread case judges the count, `apply` is a
  catalog wrapper, an eval-built closure without a `&` marker is checked, and the operators
  `_eval` evaluates inline report too ([eval-runtime.md](eval-runtime.md), "Argument counts").
  `eval` itself has no wrapper, so its report names an operator no callee carries: the JVM
  registers it by name (`JvmArityOperators.namedShape`), and wasm gives it an id one past the
  largest named funcId that only `_arity_opening` reads (`ArityReport.unbackedOperators`;
  `names(id)` stays false for it, so no dispatcher can name a real callee by that id).
- **Sizes** (2026-09-12, minimal programs, JVM `.class` / wasm Preview 1 bytes):

  | program | JVM before | after | wasm before | after |
  |---|---|---|---|---|
  | `(print (+ 1 2))` | 3,949 | 3,949 | 489 | 489 |
  | `(print (handler-case (car 1) (error (c) :e)))` | 10,508 | 10,681 | 13,709 | 13,715 |
  | `(print (mapcar (lambda (x) (* x x)) '(1 2 3)))` | 6,935 | 7,613 | 16,446 | 16,446 |
  | a `defun` + a wrong-arity `funcall` under `handler-case` | 19,964 | 20,879 | 19,650 | 19,840 |

  A program with no indirect call is byte-identical on both backends. The JVM pays +173 B on ANY
  handler-case (the sixth classification arm) and +678 B on any program with a dispatcher
  (`_arityMsg` + `_arityErr` + the funcId table); wasm pays nothing without a landing pad, +6 B when
  its dispatchers are all shaken away, and +190 B when the arms are live.

  The `apply` half on top of that (2026-09-12, same method, the four rows above unchanged by it):

  | program | JVM before | after | wasm before | after |
  |---|---|---|---|---|
  | a `defun` + a wrong-arity LITERAL `apply` under `handler-case` | 47,513 | 48,075 | 13,528 | 13,780 |
  | the same through a function VALUE (`(let ((h #'f)) (apply h '(1 2)))`) | 47,624 | 48,185 | 32,120 | 32,851 |
  | a `defun` + a RIGHT-arity literal `apply`, no `handler-case` | 7,093 | 7,522 | 11,206 | 11,206 |
  | the real cl-ppcre exercise (`asdf:load-system`) | 767,584 | 769,410 | 713,188 | 713,188 |
  | the same exercise under `handler-case` | 774,886 | 776,712 | 719,698 | 722,198 |

  The JVM pays +429 B for `_arityMsg` + `_arityChk` on any program with a guarded site, then ~6 B
  per site; wasm pays nothing at all without a landing pad -- the cl-ppcre module is byte-identical
  there, `establishesLandingPad` being false for it -- and ~2.5 KB (+0.35%) on the same corpus with
  one. The plan this landed from feared +28 KB on wasm from 14 B in every spread case pushing a 2,000-callable
  program past `DISPATCH_PAGE_BUDGET_BYTES` ([wasm-function-body-size.md](wasm-function-body-size.md));
  the shared function made it 7 B and the measurement is an order of magnitude under that.

  Naming the operator on top of that (2026-09-26, same method, programs compiled with
  `--class-name P`; the programs catch with `(program-error (c) (princ-to-string c))`):

  | program | JVM before | after | wasm before | after |
  |---|---|---|---|---|
  | `(print (+ 1 2))` | 6,024 | 6,024 | 348 | 348 |
  | `(print (handler-case (car 1) (error (c) :e)))` | 14,502 | 14,513 | 5,203 | 5,203 |
  | `(print (mapcar (lambda (x) (* x x)) '(1 2 3)))` | 11,623 | 11,775 | 6,175 | 6,175 |
  | a `defun` + a wrong-arity `funcall` of it under `handler-case` | 38,875 | 39,014 | 22,623 | 22,823 |
  | a wrong-arity `funcall` of `#'cons` under `handler-case` | 32,813 | 32,958 | 21,310 | 21,510 |
  | the same as a LITERAL `(apply #'cons '(1))` | 31,983 | 32,125 | 19,404 | 19,552 |
  | the same through a VALUE (`(let ((h #'cons)) (apply h '(1 2 3)))`) | 43,768 | 43,938 | 29,758 | 29,975 |
  | `(eval '(funcall ...))` of it -- eval makes EVERY wrapper dispatchable | 443,796 | 448,858 | 366,576 | 375,084 |

  Even a program that names no built-in itself has some dispatchable (the runtime's own
  `#'identity` / `#'eql` defaults), so a module with a report pays ~150 B on the JVM (the exception
  class constant, the names and the decode) and ~200 B on wasm (`_arity_opening` plus a handful of
  names). The eval row is the price of naming ~250 operators: their names (JVM one joined string,
  wasm one piece each) and one selector case apiece, +1.1% / +2.3%.

## Applying a value that names no function
**Invariant: applying a non-designator (`(funcall 3 1)`, a Scheme `(h 1)` over a number) signals a
catchable `type-error` reporting `Not a function: <prin1>`
(`ClosRegistry.NOT_A_FUNCTION_MESSAGE_PREFIX`), and a SYMBOL no function answers -- NIL included,
which the interpreter used to call `Not a function: NIL` -- an `undefined-function` reporting
`The function NAME is undefined`, on all four backends, through `funcall`, `apply`, `mapcar` and
every other dispatcher route.** Before (2026-09-17): the JVM leaked a `ClassCastException` (an NPE
for nil) caught only as the generic type-error text, uncaught as a Java class-cast line; wasm-GC
trapped on the closure cast straight past `handler-case`; both compiled `_apply`s answered NIL for
anything they could not call. Pinned by ci-spec `applying-a-non-function-signals-its-condition` +
standalone `uncaught-non-function-report`, `LispEvaluatorTest`
`applyingANonFunctionSignalsATypeErrorAndNilAnUndefinedFunction`, `JvmLispCompilerTest`
`compileAndRunApplyingANonFunctionSignalsTheInterpretersCondition`, `WasmLispCompilerIntegrationTest`
`ehApplyingANonFunctionSignalsTheInterpretersCondition`.

- **Detected where each backend already dispatches on the callee's representation, never in front
  of a call.** Interpreter: `LispEvaluator.apply`'s fall-through. JVM: segment 0 of every
  `_invoke_N` / `_invoke_v` dispatcher replaces `checkcast Object[]` + `checkcast Integer` with the
  `instanceof` twins (the JIT folds them into the casts that follow) and sends a miss, a
  string-designator lookup miss and `_apply`'s three silent arms to one helper,
  `_notFn(Object) -> RuntimeException` (`JvmRuntimeBuilder.buildNotFnBody`: null -> NIL, an unframed
  `String` -> symbol, else `_lispToString`). The pad recovers `type-error` from the prefix (the
  unbound-variable precedent). wasm-GC: `emitDispatchPrologue` in EH mode tests the CLOSURE first
  and `br_if`s straight to the cast, so a function value pays the same two type checks and one
  branch the symbol-first order did; the failure arms sit AFTER the dispatch (`emitDispatchEpilogue`),
  not between prologue and `br_table`. `_apply` hands every value it cannot call to the spread
  dispatcher, whose prologue reports it. A quote-framed string shares `TYPE_STRING` with a symbol, so
  the undefined arm re-tests the first byte and falls through to the not-a-function arm; a keyword
  keeps its colon (prin1), any other symbol prints as princ -- the interpreter's `symbol.name()`.
- **Class on wasm rides on a layout the module already has**: a program whose source names
  `type-error` / `undefined-function` has it baked (`usedLayoutTags`) and gets the typed instance
  (`WasmRuntimeBuilder.NotFunctionReport`, `conditionInstance`); one that does not cannot tell the
  instance from the message-only `(nil . message)` payload, so nothing new is baked. This is why the
  designator path's undefined-function is TYPED on wasm while a direct call's stub is not.
- **Outside EH mode wasm is byte-identical** and still traps (unreachable / cast failure) -- the
  uncaught-report rule above. `--no-gc` unaffected.
- **One text in every mode**: the Scheme REPL used to reword the failure (`#f is not a procedure;
  operands: (2 3)`, carried by a LispApplyException, removed) while file mode and the compiled backends
  could not. The REPL prints the condition's text now (`.kb/scheme-frontend.md`).
- **Scheme never reaches this path for its own values** (`.todo/851`, 2026-09-18): a
  lowered Scheme combination whose operator is not a known procedure goes through
  `rontolisp::%scheme-ensure-procedure` (a `functionp` check reporting
  `The object is not applicable: <scheme-print>` through `%scheme-error-message`,
  the same text `%scheme-eval-apply` reports), so `#f`, the unspecified object, a
  number and any symbol report as Scheme values before any backend dispatches on
  them. The backends carry the message; none learns a Scheme name. A Scheme
  `(h 1)` over a number therefore reports `The object is not applicable: 3`, not
  the `Not a function: 3` a CL `(funcall 3 1)` reports -- same backends, different
  front ends, each in its own terms.
- Cost (2026-09-17, a 20M-iteration `funcall` loop, 12-16 alternating runs, load 6-16): JVM
  monomorphic 23-38 -> 0-1 ms (the loop now folds entirely), polymorphic 109-114 vs 99-131 ms; wasm
  P1 EH mode mono 259 vs 262 ms, poly 766 vs 783; component poly 753 vs 804 against an A/A run of
  the SAME binary at 810 vs 850 -- inside the noise. A first cut that tested the closure AFTER the
  symbol test (three checks) measured +13% mono and was dropped. Sizes (JVM `.class` / wasm P1):

  | program | JVM before | after | wasm before | after |
  |---|---|---|---|---|
  | `(print (+ 1 2))` | 3,948 | 3,948 | 348 | 348 |
  | `(print (handler-case (car 1) (error (c) :e)))` | 11,444 | 11,483 | 952 | 952 |
  | `(print (mapcar (lambda (x) (* x x)) '(1 2 3)))` | 8,292 | 8,601 | 6,022 | 6,022 |
  | a `defun` + a `funcall` through a value under `handler-case` | 52,954 | 53,270 | 10,916 | 11,077 |
  | the same with `type-error` / `undefined-function` clauses | 52,394 | 52,710 | 10,743 | 10,954 |

## Out of scope (still)
The interactive debugger (`break`, `*debugger-hook*`, rendering a restart's `:report` or running its
`:interactive` function), condition-restart association, a `store-value` restart for
`check-type`/`assert`/`ccase`/`ctypecase` (`expandCcase` -> `expandEcase`, `expandCtypecase` ->
`expandEtypecase`), `--no-gc` catching (the GC path's `$lisp-cond` tag has no MVP equivalent, which
is why `--no-gc` rejects the forms outright rather than degrading). Postmodern is the real restart customer
(`prepare.lisp:54-66`, `transaction.lisp:63-70`); verbatim cl-postgres needs no restart system at
all, so **`restart-case` alone unblocks nothing real**.

## Tests
- Behavior in `LispEvaluatorTest` / `JvmLispCompilerTest` (`compileAndRun*` twins) / the `eh*` block
  of `WasmLispCompilerIntegrationTest`; `--no-gc` compile-error pins in `NoGcWasmCompilerTest`. Named
  groups: `conditionReport*`, `simpleConditionFamily*`, `warnRenders*`, `aRuntimeControlStringDatum*`,
  `aPrintObjectMethodStillWins*`, `aConditionWithNoReport*`,
  `readCharEndOfFileIsCatchableAsEndOfFile`, `noApplicableMethodIsCatchableAndReportsTheSameText`,
  `handlerBindSees*`, `handlerBindRunsEachClusterOnceForABuiltInErrorInnermostFirst`,
  `arefOutOfBoundsAndNegativeMakeArrayAreCatchable`, `outOfRangeSubscriptsAreTypeErrorsNamingTheirBound`
  (+2), `aTypedLoopReportsAnOutOfRangeSubscriptAsTheBoxedPathDoes`,
  `argumentShapeErrorsSignalACatchableProgramError` (+2, the compiled twins being
  `compileAndRunArgumentShapeErrorsSignalACatchableProgramError` and
  `ehArgumentShapeErrorsSignalACatchableProgramError`), `allowOtherKeysSuppressesTheKeywordCheck`,
  `compileAndRunAnUncaughtArgumentShapeErrorReportsTheSameLine`,
  `ehAnUncaughtArgumentShapeErrorReportsTheInterpreterLineBeforeTrapping`,
  `anInnerHandlerCaseShadowsAnEnclosingHandlerBind` (+3),
  `signalFallsThroughAHandlerCaseWhoseClausesDoNotMatch` (+3),
  `nonNumberArithmeticOperandsSignalCatchableTypeErrors`,
  `argumentTypeErrorsNameTheOperatorBeyondArithmetic` (+2), the restart block (15-16 cases each),
  `compileRuntimeErrorDispatchScalesPastTheBranchLimit`, `anUncaughtCondition*`, `ehUncaught*`, and
  the `compileAndRunHandlerCaseIn*` block -- which must COMPILE, LOAD and RUN the class, since the
  broken class was written without complaint and only failed at link time.
- Location lines: `RontoLispCliStreamsTest`'s `anUncaught*`/`anAsyncBodys*` (interpreter, `java -cp`,
  `java -jar`), `cli/UncaughtReportParityTest` (both backends, every shape above: tail calls,
  `labels`, macros, a loaded file, library callbacks, methods, nested defuns, async chains,
  fused trees, typed loops, continuations, nothing located), `am.ik.jvm.LineNumberTableTest` (the
  attribute through the relaxer, shaker, splitter and augmenter).
- Gates in `LispMacroExpanderTest`: `conditionNarrowing*`,
  `anExplicitFormatControlInitargForcesTheRenderer`, `aComputedDatumMakesTheConditionSetUnknowable`,
  `aDirectiveFreeLiteralFormatControlStillDeclinesTheRenderer`,
  `aHandlerCaseThatNeverNamesItsConditionDoesNotRouteReports`,
  `ignoreErrorsRoutesReportsOnlyWhereASecondValueCanBeRead`,
  `a{ThrowOnlyConstructionDoesNot,HeldConditionStill}RouteReportsWhereMessagesAreLazy`,
  `aNonOperatorRestartNameDoesNotFlipRestartMode`,
  `anOperatorPositionRestartFormStillFlipsRestartMode`,
  `needsSignalClauseMatchRequiresBothASignalAndACatchingForm`,
  `keywordTailProblemHonoursAllowOtherKeys`,
  `conditionNarrowingMarksProgramErrorConstructibleOnlyBehindALandingPad`,
  `establishesLandingPadReadsOperatorPositionOnly`; plus
  `WasmLispCompilerTest.typedErrorWithLambdaReportCompilesOutsideEhMode`,
  `ByteCodeWriterTest.generateAndRun{TypedCatch,CatchAny}Handler`,
  `WasmTreeShakerTest.shakesEhModeModules`, `RontoLispCliTest`, `JvmFloatArrayTest`.
- ci-spec: `condition-objects`, `condition-types`, `condition-report-printing`,
  `signal-runtime-control-string`, `handler-case-catches-typed-and-plain-errors` (+2),
  `handler-case-in-argument-position`, `restart-system`,
  `signal-declines-an-unmatched-handler-case`, `no-applicable-method-report`,
  `non-number-arithmetic-operands-are-catchable`,
  `argument-type-errors-name-the-operator-beyond-arithmetic`,
  `argument-shape-errors-signal-program-error`,
  `out-of-range-subscripts-are-type-errors-naming-their-bound`,
  `applying-a-non-function-signals-its-condition`,
  `runtime-type-dispatch-residue`,
  `runtime-type-dispatch-and-symbol-designators`, `postmodern-language-incidentals`, plus the
  `standalone:` list. Their presence puts the concatenated program in EH mode, so
  `CiSpecE2eTest.runBackend` passes `-W exceptions=y` to both wasmtime invocations.
  `ParseNumberE2eTest` and `JzonE2eTest` cover the report text and the runtime dispatch.
