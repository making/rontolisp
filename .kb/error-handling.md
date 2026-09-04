# Error handling: unwind-protect + condition objects + handler-case + restarts

**Backend contract: interpreter, JVM and both wasm-GC backends (Preview 1 + `--component`, incl.
serve) are full; only `--no-gc` rejects `unwind-protect` / `handler-case` / `ignore-errors` at
compile time** (no condition objects in its value model).

wasm-GC catching uses the WebAssembly exception-handling proposal and is GATED: only a program
containing one of the three catching forms is compiled in **EH mode** (one `$lisp-cond` tag,
`try_table`/`throw`) and only such a program needs wasmtime 37+. Anything else is byte-identical
to a build that never knew about EH.

**A NON-LOCAL EXIT is not a condition**: it passes through a `handler-case` uncaught while still
running every `unwind-protect` cleanup -- for cross-lambda `return-from`/`go` and for
`catch`/`throw`, which share one exit channel (`.kb/do-return-block.md`, which also owns the
`ctx.blockExitTag`/`blockExitChannel` gate).

**The three-point catchability spectrum**: interpreter catches `LispEvalException` only, JVM any
`RuntimeException`, wasm-GC only `$lisp-cond` throws (raw traps -- failed ref.cast, integer
divide by zero, `unreachable` -- are uncatchable there and skip unwind-protect cleanups).

## Phase 1 -- unwind-protect
- **Interpreter**: `LispEvaluator.evalUnwindProtect` = `try { protected } finally { cleanups }`.
  Both unwind channels are Java exceptions (`LispEvalException`, `BlockReturnSignal`), so error
  AND `return`/`return-from` run the cleanup; a cleanup that signals replaces the pending unwind
  (CL: newer exit wins).
- **JVM**: `ByteCodeWriter.writeExceptionTable` + `ExceptionTableEntry`. Emitters stay frame-free
  (raw output is version-50 and verifies handlers without a StackMapTable --
  `ByteCodeWriterTest.generateAndRun{TypedCatch,CatchAny}Handler`); version-61 handler frames are
  synthesized by the offline `StackMapAugmenter` pass ([stackmap-augmenter.md](stackmap-augmenter.md)).
  Each `JvmLispCompiler.Ctx` carries a per-method `exceptionTable`; the four method-writing sites
  (main/chunks/defuns/lambdas) emit it. `JvmUnwindProtectCompiler` lays out protected region /
  cleanup+GOTO / catch-any handler (store, cleanup, ATHROW).
  - The `return` channel is a plain GOTO and would skip both cleanup copies, so `Ctx.unwindScopes`
    records active scopes and `JvmReturnCompiler` compiles every ESCAPED scope's cleanups inline
    before its GOTO (escaped = `scope.blockDepth >= blockTargets.size()`, innermost first). Those
    inlined ranges are `holes` the scope's own exception entries exclude -- a throw from an inlined
    cleanup must not re-enter its own handler (it still lands in OUTER handlers).
    `JvmClassShaker` is exception-table-aware.
- **A cleanup's VALUES are discarded, its value COUNT included**: the cleanup sequence is bracketed
  by a save/restore of the `%mv-spill` channel on all three backends, and the save lives in each
  backend's SHARED cleanup emitter so the copies a `return`/`go` inlines get it too.
  [multiple-values.md](multiple-values.md).
- **with-\* retrofit**: `expandWithOpenFile` / `expandWithOutputToString` /
  `expandWithInputFromString` / the three usocket `with-*` take a `boolean unwindProtect`
  (default true); WASM call sites pass `false`. So interpreter/JVM close on EVERY exit, WASM on
  normal exit only.
- Known limit: the compile-path special-`let` restore on a `return` across the binding
  (`.kb/dynamic-special-variables.md`).

## Phase 2 -- condition objects
- A condition is a CLOS-subset instance (`.kb/instance-syntax.md`): `(%obj-new '%class-<name>
  slots...)`, printed `#<NAME :SLOT value ...>`, NOT a list.
- `ClosRegistry`'s constructor seeds the hierarchy (`condition` > `serious-condition` > `error` >
  `simple-error` + `parse-error`, `type-error`, ...; `warning` > `simple-warning`) from
  `CONDITION_SEEDS`, ONE static list. Two consumers must keep READING it, never a copy: the
  constructor, and `PackageRegistry.CL_CONDITION_TYPES` = `ClosRegistry.CONDITION_CLASS_NAMES`,
  which makes every seeded name a `cl` symbol (`.kb/packages.md`).
- Four classes carry `format-control`/`format-arguments` beyond CLHS's slot lists --
  `type-error`, `arithmetic-error`, `unbound-variable`, `undefined-function` -- because those are
  what a BUILT-IN error is synthesized as and the pair is how it carries its message.
  `simple-type-error` therefore adds nothing, so both keep their old `%obj-ref` indexes.
- `define-condition` = `defineConditionToDefclass` -> `expandDefclass` (top-level-only on the
  compile path, spliced by `expandTopLevelDefinitions`); `(:report x)` registered (string or
  `(lambda (c s) ...)` AST), `:documentation` dropped. **Lite multiple parents**: the FIRST parent
  provides the slot layout, the rest join the ancestor set only (`registerExtraAncestors`) so
  `typep`/`handler-case` match through them. `findClass` also resolves a package-qualified
  spelling to a class registered under the plain name.
- `error`/`warn`/`signal` share `expandSignalDesignator`: a string designator takes the LEGACY
  `(%error message)` path (byte-identical output); a quoted-type designator binds initarg temps,
  builds the instance via the registry slot layout (`buildTypedConstruct`; unknown class or
  non-keyword args -> values fill the layout positionally, an unregistered type is an error) and
  signals `(%error-cond instance message)`; an object designator dispatches at runtime. A literal
  `(make-condition 'type ...)` argument re-routes through the typed path.
- **A datum that is a STRING at run time is a format control and the arguments after it are its
  format arguments** -- `(let ((c "~a-~a")) (error c 1 2))` reports `1-2`. `expandObjectSignal`'s
  string arm renders `(%fmt-render datum (list args...))`; three callers feed it (the compile-path
  fallthrough, the interpreter's runtime-type-dispatch arm -- whose symbol test never sees a
  string, since the object expansion tests `stringp` first -- and `%error-runtime`'s `t` clause).
  Argument forms are evaluated only on the string arm, so every datum-only call keeps its previous
  expansion byte for byte.
  - **The rendering is EAGER**: the instance carries rendered text in `format-control` and nil
    `format-arguments`. Storing control + arguments would be closer to CLHS and retire the
    double-render deviation, but only on this path -- the literal path renders without a renderer
    in the artifact, which is why there are two. **Re-evaluate if** the renderer becomes free
    (tree-shakeable per directive, or the literal path stops being a concatenation).
  - The lite `#'error`/`#'warn`/`#'signal`/`#'cerror` WRAPPERS forward the datum only
    (`BuiltinFunctionWrappers.SIGNAL_FUNCTIONS`), so `(apply #'error c '(1 2))` drops the
    arguments on the compiled backends -- documented lite semantics, as for initargs.
- Channels: interpreter `LispEvalException` carries a nullable `condition()`; JVM `%error-cond`
  stores the instance into the emitted `private static ThreadLocal _condTl` and throws
  `RuntimeException(message)`. Field + `<clinit>` and the `_hcDepthTl` counter are emitted only
  when used (`JvmLispCompiler.ConditionChannel`). WASM: `%error-cond` traps like `%error`.
- `makeTypeTest` takes a `ClosRegistry` and has a class branch (descendant-tag membership, `equal`
  on the car -- WASM content-safe); `typecase`/`etypecase` thread the registry. `with-slots` is a
  read-only expansion (`let` over `slot-value`; assignment does NOT write back -- no symbol
  macros). `parseDefclassSlot` accepts and drops the `:documentation` slot option.

## Phase 3 -- handler-case / ignore-errors
Surface: `(handler-case expr (type ([var]) body...)... [(:no-error ([var]) body...)])`; clause
types are `makeHandlerTypeTest` = `makeTypeTest` + an exact-tag fallback for unknown names. A
condition-less throw is caught as a synthesized `simple-error` with the message in slot 1. No
match -> rethrow (the JVM rethrow RESTORES `_condTl` first). `:no-error` runs on normal completion
OUTSIDE the handler, at most one variable. `ignore-errors` = `expandIgnoreErrors` over
`(error (c) (values nil c))`.

- **Interpreter** (`evalHandlerCase`): `try/catch (LispEvalException)`; `BlockReturnSignal` passes
  through; clause tests eval'd against a child env. A per-evaluator
  `ThreadLocal<ArrayDeque<List<LispVal>>> handlerCaseTypes` holds the clause TYPE SPECIFIERS of
  every established handler-case; `%signal-cond` raises only when some active clause type MATCHES
  (`anyHandlerCaseMatches`), else nil. The built-in seam in `LispEvaluator.apply` wraps an escaping
  `IndexOutOfBounds`/`NegativeArraySize`/`Arithmetic`/`ClassCast` into a `LispEvalException`, so
  `aref` out of range and `(make-array -1)` are catchable here too.
- **JVM** (`JvmHandlerCaseCompiler`): catch-any exception-table region over the protected
  expression (unwind-protect machinery, holes included -- a `return` inside decrements the depth
  through the `UnwindScope` cleanup channel, the internal `%hc-depth-dec` form); the handler
  reads-and-clears `_condTl`, synthesizes the simple-error from `Throwable.getMessage()`
  (quote-framed) when empty, then compiles clause tests/bodies as ORDINARY Lisp forms over a
  pseudo-local (`ctx.locals.put("__hc_cond$<slot>", slot)`, shadowed mapping saved and restored).
  `JvmSignalCondCompiler` checks `_hcDepthTl` (null = 0).
  - **The operand-stack spill (why it works in an ARGUMENT position).** Unlike unwind-protect
    (handler ends in ATHROW, never rejoins), the handler-case handler MERGES back into the normal
    path -- and the JVM discards the operand stack on handler entry, so the two edges disagree by
    exactly the operands the ENCLOSING form had evaluated and the class does not verify.
    `Ctx.spillOperandStack()` saves the live operands into fresh locals BEFORE the protected region
    and `Spill.restore` reloads them past the merge; a handler-case compiled as a statement spills
    nothing and stays byte-identical. Liveness comes from `am.ik.jvm.OperandStack`, a typed model
    fed by `Ctx.emit`/`emitU2` (it also supplies a real `max_stack` and raises on a merge-point
    mismatch rather than writing an unverifiable class). An object under construction (`new`,
    pre-`<init>`) can never be spilled -- tagged `Slot.UNINIT` and rejected. A `return` escaping a
    spilled region reloads operands from the outermost escaped `SpillScope`
    (`JvmReturnCompiler.emitStackUnwind`).
- `FreeVarAnalyzer` learned `handler-case` (clause var is BOUND in the clause body),
  `ignore-errors` and `with-slots`.

## WASM: wasm-GC catching
- **EH-mode gate** (`WasmLispCompiler.compile`): the program (post pre-passes, libraries spliced)
  is scanned for `handler-case`/`ignore-errors`/`unwind-protect` head symbols. Only then: the tag
  section (id 13, between memory and global) with ONE tag `$lisp-cond` whose type reuses
  `TYPE_PRINT_VAL` (`((ref null eq)) -> ()`), the handler-depth global (a `(mut i32)` appended
  AFTER the user globals, `Ctx.ehDepthGlobalIndex`), the throw path and the entry wrappers.
  Byte-identity for a program without the forms is stash-dance proven across P1 / component base /
  http-client / sockets / serve / --optimize / --dynamic / --no-wasi exports / --no-gc.
- **Throw path** (`WasmErrorCompiler`): in EH mode `%error`/`%error-cond` evaluate their
  arguments, build the payload cons `(condition-instance . message-string)` (instance = nil for
  plain `%error`) and `throw $lisp-cond`; outside EH mode they stay a bare `unreachable` without
  evaluating anything. `throw` is stack-polymorphic like `unreachable`, so call sites are unchanged.
- **Top-level trap shape** (`WasmEmitHelper.emitCatchAllPrologue/Epilogue`): in EH mode every
  export wrapper body (incl. serve's `%http-dispatch`) runs inside `block` +
  `try_table (catch_all)` whose landing is `unreachable`; the normal path `return`s from INSIDE the
  try_table (no result blocktype needed whatever the signature). The ENTRY function (`_start`/`run`)
  uses the reporting variant instead.
- **handler-case** (`WasmHandlerCaseCompiler`, mirroring the JVM layout):
  `block $done (result ref null eq)` [+ optional return trampoline] + `block $h` +
  `try_table (catch $lisp-cond $h)`; the landing splits the payload, synthesizes the `simple-error`
  when the instance is nil (the message is already a quote-framed Lisp string -- no re-framing,
  unlike the JVM's `getMessage()`), dispatches tests and clause bodies as ordinary Lisp over the
  `__hc_cond$<slot>` pseudo-local, and rethrows the ORIGINAL payload on no match. `:no-error` runs
  outside the region. The depth global is inc/dec'd around it.
- **unwind-protect** (`WasmUnwindProtectCompiler`): `block $u (result exnref)` +
  `try_table (catch_all_ref $u)`; landing = cleanups over the exnref, `throw_ref`. Normal exit
  stashes the value, runs cleanups, `br $done`.
- **The return channel -- exit trampolines** (`Ctx.unwindScopes` + `WasmReturnCompiler`): each
  protected region (unwind-protect AND handler-case, whose "cleanup" is `%hc-depth-dec`) pushes a
  `UnwindScope{cleanupForms, blockDepth, trampolineDepth}`. A `return` whose target `%block` lies
  outside the innermost scope branches to that scope's trampoline block -- emitted lexically
  OUTSIDE the try_table, only when an enclosing `%block` exists -- which runs the cleanups and
  cascades to the next escaped scope's trampoline or the target block (innermost first). Being
  outside the try_table is the structural equivalent of the JVM `holes` mechanism.
- **Walkers**: `WasmSections.scanInstr` (shared by `WasmImportInjector`) knows `throw` (0x08, tag
  immediate), `throw_ref` (0x0A), `try_table` (0x1F, blocktype + catch-clause vector) and the
  `exnref` valtype (0x69); tags are their own index space so function renumbering is unaffected.
  P1 EH + `--optimize` compose (`WasmTreeShakerTest.shakesEhModeModules`).
- **Component path**: core-module-internal only -- no component-level section changes, blobs
  untouched, `--emit-wit` unchanged, async lift unmodified.
- **V8 hosts** (playground / jco): wasm-EH with exnref is default-on in Chrome 137+ / Node 24+;
  Node 22 needs `--experimental-wasm-exnref`.
- **usocket typed conditions**: `usocket.lisp` defines the hierarchy (`socket-condition` with a
  `message` slot + echo `:report`, `socket-error`, `connection-refused-error`, ...) and wraps
  `socket-connect`/`socket-listen`/`socket-accept` bodies in `(usocket::%usock-guard form)`,
  expanded per backend (`expandUsocketGuard`): interpreter/JVM = `handler-case` +
  `usocket::%usock-resignal` (re-signals as `usocket:socket-error`, always that class), WASM =
  pass-through. The shim source is parsed ONCE and cached for all backends, so the branch cannot be
  a reader feature.

## The read family signals a TYPED end-of-file
`read-char` / `read-byte`, and `read-line` with an explicit non-nil `eof-error-p`, signal the
SEEDED `end-of-file` class on all four backends, so `(handler-case (loop ... (read-char s) ...)
(end-of-file (e) ...))` terminates. `ClosRegistry` seeds a `:report` (`END_OF_FILE_MESSAGE`,
`"end of file"`).

- **Interpreter**: `Environment.endOfFile()` throws a `LispEvalException` carrying
  `ClosRegistry.newEndOfFileCondition()` -- a static factory, since `Environment` has no registry
  in scope. Sound because the class is SEEDED (same slot-less layout in every registry) and
  `handler-case` dispatches on the instance TAG, not layout identity.
- **Compiled**: one shared CALL-SITE lowering, `LispMacroExpander.expandReadEofSignal`, applied by
  `Jvm/WasmExprCompiler` -- the built-in is called with the backend's `(nil nil)` eof parameters
  and the expansion tests the nil result. Sound for exactly these three operators because a
  successful read answers a character / string / integer and never nil. Runtime helpers keep their
  old throw as a BACKSTOP. Returns null (no lowering) when the call cannot signal: a literally nil
  `eof-error-p`, or an omitted one on `read-line`.
- Two gates easy to miss: `mayCreateInstances` scans the SOURCE program, so the read family is in
  `constructsInstance` (peek-char under its own name, its eof-error-p one argument later); and
  `#'read-char`/`#'peek-char`/`#'read-byte` joined
  `BuiltinFunctionWrappers.REFERENCE_GATED_FUNCTIONS`, since their wrappers now construct a
  condition and were previously injected into EVERY program.
- **The `--component` socket rewrite needs the same lowering under its ALIAS.**
  `WasmSocketsRewrite` maps `(read-char s)` to `(%io-read-char s)`, falling through to
  `rontolisp::%read-char-raw`; lowering only the public name left every sockets.lisp-splicing
  component with the OLD uncatchable trap at EOF. `WasmExprCompiler` therefore applies the lowering
  to `%read-char-raw`/`%read-byte-raw`/`%read-line-raw` too, and `constructsInstance` lists their
  qualified spellings.
- **`read` is deliberately NOT in this family** and neither is default `read-line`: both answer nil
  at end of input, and `read`'s datum may legitimately BE nil, so the nil-result test would be
  ambiguous. `read` has no `eof-error-p` parameter.
- Pinned by `readCharEndOfFileIsCatchableAsEndOfFile` in all three per-backend suites and the
  `postmodern-language-incidentals` ci-spec case.

## A condition's `:report` is what PRINTS it
**Invariant: the text a condition REPORTS has exactly one implementation,
`%condition-report-str`, and both the printer and the signal message go through it.**
`princ` / `princ-to-string` / `format ~A` answer the report; `prin1` / `~S` keep the
`#<TYPE :SLOT value ...>` instance syntax -- CLHS's escape-mode split, matching SBCL.

- **It rides the `print-object` seam** (`.kb/clos.md`): `expandPrintObjectHook` fires when the
  program defines a `print-object` method **or** can build a condition; the escape-off arm of
  `%print-object-str` becomes, in effect, `(if (%obj-p x) (or (%condition-report-str x)
  (%princ-to-string x)) (%princ-to-string x))`. A `print-object` method on a condition class still
  wins in BOTH escape modes (the method route is tested first).
- **`%condition-report-str` answers nil when the class reports nothing** and every caller supplies
  its own fallback, so an under-approximating gate degrades to pre-report text instead of failing.
- **The class partition** (`conditionReportGroups`): a class's report is its own `:report`, else
  the nearest ancestor's along the SLOT-LAYOUT parent chain, else -- when it carries
  `format-control`/`format-arguments` -- CLHS's `simple-condition` report,
  `(apply #'format stream control arguments)`. Groups are keyed by report owner / slot-index pair,
  NOT one clause per class: cl-postgres registers 100+ condition classes and a per-class dispatch
  is the same 90 KB-in-one-method trap the runtime type dispatch hit
  ([jvm-method-size-limits.md](jvm-method-size-limits.md)).
- **`%format-condition` renders through `%fmt-render`** -- the shared runtime renderer a computed
  `(format nil ctrl args)` and `#'format` use, so the directive set is identical on all four
  backends and to the literal expansion's ([format.md](format.md)). A control that is a FUNCTION
  is called on the stream through FIXED-ARITY `funcall`s for 0-3 arguments: `apply` would drag the
  whole wasm eval runtime into every program that prints a condition. A nil control answers nil.
- **The gate is `mayCreateConditions(program, registry)`**, the CONDITION half of
  `mayCreateInstances` sharing its `constructsInstance` case split (plus
  `#'error`/`#'warn`/`#'cerror`, and `%obj-new` restricted to `%class-` tags descending from
  `condition`). Answered TWICE in `expandTopLevelDefinitions` -- once on the source program (whose
  only condition may be the `simple-error` a `handler-case` synthesizes, with no definition to
  splice) and once on the expanded program (where `define-condition` became `%obj-new`). Recorded
  in `ClosRegistry.routesConditionReports()` rather than a `Ctx` flag, so it needs no
  `WasmAsyncEmit.freshCtx` line (the trap Phase 4's `restartMode` hit).
- **The generated defuns must not contain the SYMBOL `with-output-to-string`.** The wasm-GC EH gate
  (`programUsesEhForm`) scans for it -- its interpreter/JVM expansion rides `unwind-protect` -- and
  that scan runs AFTER `expandTopLevelDefinitions` splices these defuns in, so leaving the macro
  there forced EH mode on every program that merely signals a typed condition. `renderedToString`
  pre-expands it; pinned by
  `WasmLispCompilerTest.typedErrorWithLambdaReportCompilesOutsideEhMode`.
- **The interpreter loads the same generated AST** (`ensureConditionReportRuntimeLoaded`) and
  RE-loads whenever the registry it partitions changed (a stamp over class and report counts),
  since a `define-condition` can follow the first print.
- Cost: a program that can build a condition grows by the renderer plus the runtime format defuns
  (~11.6 KB of wasm on a 300 KB module when the renderer was a cut-down lambda; the shared renderer
  is bigger, injected once per program). Not tree-shakeable -- reachable from every print site.
- **Lite**: the rewrite is per CALL FORM, so a condition reached through a FUNCTION VALUE
  (`(mapcar #'princ conditions)`) gets the raw conversion, exactly as a `print-object` method does.
  A `~A` with a COMPUTED control string does route (the renderer's `~a` arm is an ordinary
  `(%princ-piece ...)` form in the injected `format-render.lisp` defuns).
- **Known deviation**: the string-designator signal path renders EAGERLY, so a synthesized
  `simple-error` carries an already-rendered message and printing renders it a SECOND time --
  visible only when the rendered text still contains a live directive (`(error "~a" "~a")` prints
  `NIL` where CL prints `~a`). Rendering unconditionally is the CLHS report and keeps
  `~%`-bearing controls with no arguments correct.
- Pinned by `conditionReport*`/`simpleConditionFamily*`/`warnRenders*`/
  `aRuntimeControlStringDatum*`/`aPrintObjectMethodStillWins*`/`aConditionWithNoReport*`
  (`LispEvaluatorTest`), their `compileAndRun*` twins (`JvmLispCompilerTest`), the
  `ehConditionReport*`/`ehRuntimeControlString*` block, and ci-spec
  `condition-report-printing` + `signal-runtime-control-string`.

## The condition floor is narrowed to what the program can construct
The compile path shrinks the condition runtime along three axes; the interpreter never narrows
(its world stays open at run time), and every narrowing is IMPOSSIBILITY-based.

- **`conditionNarrowing`** (`LispMacroExpander`) scans the expanded program for the constructible
  `%class-` tag set + whether any site can hand `%format-condition` an UNRENDERED control. Tag
  sources: literal datums of the signal family
  (`error`/`warn`/`signal`/`cerror`/`make-condition`, `make-instance` of a condition class),
  literal-tag `%obj-new`, plus always the synthesized simple-* three. BAILS to `none()` on a
  computed datum, `eval`/`symbol-function`/`fdefinition`, an escaping `#'error`-family value or
  quoted designator in data, `--dynamic`, restart mode. Clause HEADS of
  `handler-case`/`handler-bind`/`case` are type specifiers, not calls; the generated
  `%error-runtime`/`%error-rt-*` defuns are exempt from the quoted-designator bail (their `%obj-new`
  tags are still collected). Name forgery from computed strings can reach a pruned arm -- the
  failure is the caller's fallback report text, never a lost signal.
- **`conditionReportGroups` filters by the tag set**: the seeded
  `end-of-file`/`unbound-slot`/`simple-type-error` arms leave artifacts that cannot construct them.
- **`%format-condition` declines the renderer** when every possible control is a directive-free
  literal (or nil) with nil arguments -- the common case, since every string-datum signal site
  pre-renders (`formatMessagePieces`). Only an explicit `:format-control` initarg (surface keyword
  check + the baked `:initform`/`:default-initargs` cons check inside generated constructors'
  `%obj-new`) forces it back. On zlib the declined renderer plus its transitive string machinery
  was **-61 KB**. One corner moves TOWARD CLHS: the double-render deviation requires the renderer,
  so a declined artifact prints a rendered-once message where the interpreter re-renders it.
  **Re-evaluation trigger**: if that bites, render once EVERYWHERE, not by un-declining.
- **`WasmInstanceLayouts.emit` takes a used-tag set** (`usedLayoutTags`): a `%class-`/`%struct-`
  layout ships only when its tag or bare name occurs as a symbol in the final program (plus the
  simple-* three the handler lowering synthesizes during Pass 2), with null (= bake all) under
  `--dynamic`, an embedded eval runtime, restart mode, subclass enumeration,
  `find-class`/`change-class`/`allocate-instance`/`symbol-function`/`fdefinition`. The JVM backend
  already interned per referenced tag (`LayoutPool`). A `handler-case (error (e) ...)` clause keeps
  every error-descendant layout through its lowered ancestor tag list -- correct, those are
  testable.
- **`needsRuntimeErrorDispatch` no longer misreads handler clauses**: `(handler-case b (error (e)
  ...))` used to parse as `(error <computed> ...)` and bake the whole per-class construction
  runtime into EVERY handler-case artifact. With clause heads skipped, the 89,138 B handler-case
  probe is **23,341 B**.

Pinned by `LispMacroExpanderTest.conditionNarrowing*` /
`anExplicitFormatControlInitargForcesTheRenderer` /
`aComputedDatumMakesTheConditionSetUnknowable` /
`aDirectiveFreeLiteralFormatControlStillDeclinesTheRenderer`, and behaviorally by the condition
block of the three per-backend suites plus the ci-spec condition cases.

## The routing gate asks whether a condition can be NAMED
`mayCreateConditions` cannot part company with `mayCreateInstances` by proving the body will not
signal -- the handler prologue synthesizes a `simple-error` for a caught RAW trap however the body
fails. What decides the gate is whether program code can ever HOLD that instance.

- **`handler-case` counts only when some clause binds a variable its own body mentions**
  (`handlerCaseBindsCondition`). Otherwise the instance never leaves the landing pad: clause TYPE
  tests read its tag, an unmatched clause rethrows the ORIGINAL payload, and an uncaught
  condition's text comes from that payload's message string. The occurrence test is deliberately
  blunt (a mention in quoted data or under a shadowing rebinding counts). `:no-error` binds the
  protected form's VALUES.
- **`ignore-errors` counts only where a SECOND value can be read** (`receivesMultipleValues`, a
  whole-program answer): its expansion is `(error (c) (values nil c))`, and any occurrence of
  `multiple-value-bind`/`-list`/`-call`/`-setq`/`-prog1`/`nth-value`/`%mv-spill` turns it back on.
- **A clause HEAD is not a call, and that skip is shared.** `evaluatedClauseForms` is the one
  helper answering "which sub-forms of this clause-bearing operator are EVALUATED", used by this
  scan and by `needsRuntimeErrorDispatch`. **A new scan that walks a program as code goes through
  it** -- the same misread has cost three times (a tagbody-tag `CONTINUE`, the dispatch runtime,
  this).

Measured at `--optimize=size`: `(print (handler-case (+ 1 2) (error () 0)))` drops 23,216 ->
5,713 B and `(print (ignore-errors (+ 1 2)))` 22,782 -> 5,638 B (-75%). The renderer's 58-byte
defun in a program that genuinely routes is dead-code-eliminated by `--optimize` when no print
site reaches it; gating it on a pre-Pass-2 scan for printing operators is NOT sound, because
`format`'s `~A` lowers to `%princ-piece` after that scan would have run. Pinned by
`LispMacroExpanderTest.aHandlerCaseThatNeverNamesItsConditionDoesNotRouteReports` /
`ignoreErrorsRoutesReportsOnlyWhereASecondValueCanBeRead`.

## Signal messages are lazy on wasm-GC
**Invariant: on the wasm-GC backends a signal's message string is rendered only where a condition
value can reach program hands -- never at the signal point.** In EH mode the entry function's
landing pad is a second payload reader (`WasmUncaughtReportCompiler`), so both halves are
observable and both gates below go broad. Outside EH mode nothing is observable and nothing is
rendered -- byte for byte unchanged.

- **`WasmErrorCompiler.compileCond` / `WasmSignalCondCompiler` never compile their message
  operand** (payload cdr = nil), unconditionally: `%error-cond`/`%signal-cond` always carry a real
  instance in the car. The instance operand still compiles (initargs are evaluated at construction
  per CL).
- **A plain `%error`'s message operand compiles only when `Ctx.condMessagesObservable`** -- its
  message IS what a caught raw trap becomes a `simple-error` from AND the only text the entry
  landing pad has. Forced on under restart mode / `--dynamic` / EH mode; copied in
  `WasmAsyncEmit.freshCtx`.
- **The routing gate narrows on wasm OUTSIDE EH MODE**: `expandTopLevelDefinitions` takes
  `lazyConditionMessages` (`WasmLispCompiler` passes `!reportsUncaught`; JVM/interpreter always
  false). Under it the answer is `LispMacroExpander.mayHoldConditions`: `mayCreateConditions` minus
  the throw-only constructions (literal-typed `error`/`cerror`, `signal`, the read family's EOF
  lowering), keeping handler-case-that-binds, ignore-errors-under-mv, `make-condition`,
  `make-instance`/`allocate-instance`/`change-class` of a condition (or unknowable computed) class,
  the `#'error`-family escapes, `load`, and typed `warn`. The keyword constructor every
  `define-condition` splices is exempted by SHAPE (`conditionConstructorName`:
  `(defun N (...) (%obj-new '%class-COND syms...))`) unless another form references N -- without it
  every library that merely DEFINES conditions (chipz) kept the whole renderer.
  - **`reportsUncaught` is a PRE-SCAN, not the definitive `ehMode`.** The gate runs inside
    `expandTopLevelDefinitions`, before the passes that finish deciding EH mode, so
    `WasmLispCompiler` scans for triggers that can accompany a signal: a catching/cleanup form
    (`programUsesEhForm`), `catch`/`throw`, restart mode, async mode. The one trigger it cannot see
    is a cross-lambda `return-from`, lowered afterwards (`CrossLambdaExitLowering`, which must run
    after the expansion or a GENERATED dispatcher's `return-from` would go unlowered). A program
    whose SOLE EH trigger is one of those keeps the narrow gate, so its landing pad prints a plain
    `%error`'s message and an empty report for a typed condition. **Re-evaluate if** the
    cross-lambda lowering ever becomes safe to run first.
- **`%no-applicable-method` signals VALUES, not prose**:
  `(error 'no-applicable-method-error :%nam-operation tail :%nam-datum-class (%class-designator
  arg))` against a class seeded ON DEMAND (`ClosRegistry.ensureNoApplicableErrorSeeded` --
  constructor-seeding is deliberately not unconditional, the `ensureMopClassesSeeded` lesson; slot
  names are %-fenced so `registerSlotPosition` cannot make a user slot ambiguous). Its `:report`
  lambda renders the byte-identical old text (`"No applicable method: ~a~a"`). Injection moved
  BEFORE the report-renderer injection (its tag must be in `conditionNarrowing`'s set) and AFTER
  the routing answer (a dispatcher alone must not flip routing on). Deliberate narrowing: a
  `(simple-error ...)` clause no longer matches this error; CLHS specifies only `error`.

Measured on zlib `--optimize=size`: P1 127,026 -> 117,118 (-7.8%), component 131,677 -> 121,723,
gunzip output byte-identical.

**Why the remaining floor is a floor.** zlib still carries `FUNC_PRINT_VAL` + `FUNC_PRINC_VAL` +
`FUNC_PRIN1/PRINC_TO_STR` + `%PRINT-OBJECT-STR` + the `PRINT-OBJECT` dispatcher + chipz's
`print-object` method (~4.6 KB): the entry edge is `%SEQ-TO-STRING` (the `coerce`-to-string
runtime, reachable through `%FILL-RUNTIME`/`%REPLACE-RUNTIME` from the inflate tables), whose
element conversion IS princ semantics, and once one princ site exists the print-object seam and
both escape modes keep the full value printers. Cutting further means splitting the printer (a
narrow string/char/symbol/integer renderer for coercions and `%string-concat`, leaving
`FUNC_PRINT_VAL`'s cons/array/instance arms to printing programs) -- deliberately not taken: a
split must prove the narrow renderer identical over the whole designator domain on both wasm
backends. **Re-evaluation trigger**: a program family whose artifacts carry the printers ONLY
through `%seq-to-string`/`%schar-set-runtime` and where those bytes matter.

Pinned by `noApplicableMethodIsCatchableAndReportsTheSameText` (evaluator) / `compileAndRun...`
(JVM) / `ehNoApplicableMethodIs...` (wasm), the gate by
`LispMacroExpanderTest.aThrowOnlyConstructionDoesNotRouteReportsWhereMessagesAreLazy` /
`aHeldConditionStillRoutesReportsWhereMessagesAreLazy`, and ci-spec
`no-applicable-method-report`.

## An uncaught condition reports ONE line, the same one, on all four backends
**Invariant: a signaled condition escaping the top level writes exactly
`Unhandled condition: <report>` to standard error, then the process exits the way it always did.**
Built from `compiler/UncaughtReport.PREFIX` at all three emission sites; the report text is the
one `princ` writes.

- **Interpreter / compile failures** (`RontoLispCli.runReporting`): only `main` catches -- `run`
  still throws, so an embedded caller keeps the exception with its type and cause. A
  `LispEvalException` gets the shared wording; anything else (read error, compile failure, bad
  command line) is a rontolisp diagnostic and says `error:`, keeping the `file:line:column:` prefix
  (`locateCompileFailure`). A RUNTIME condition carries no such prefix on any backend,
  deliberately: the position table records on the COMPILE path only, because recording it in the
  interpreter would put a `file:line:` on runtime error text that `ci-spec.yaml` and the doc
  examples pin byte for byte (`SourceProvenance`, whose javadoc holds the re-evaluation trigger).
  `RONTOLISP_DEBUG` (SET, any value) additionally prints the JVM trace.
- **JVM** (`JvmUncaughtHandler`): a last exception-table entry over the whole of `main` catching
  `RuntimeException` -- the same width `handler-case` catches here. Prints the line, EMPTIES the
  stack trace (unless `RONTOLISP_DEBUG`) and RETHROWS. Not `System.exit(1)`: a compiled class's
  `main` is invoked in-process by ~110 assertions in this project's own tests and by any embedder.
  The launcher supplies exit 1 and echoes one line (wasmtime's trap-report role); its javadoc
  carries the re-evaluation trigger for removing that echo.
- **wasm-GC, EH MODE ONLY** (`WasmUncaughtReportCompiler`): the entry function wraps its body in
  `block $trap` + `block $cond (result (ref null eq))` +
  `try_table (catch $lisp-cond 0) (catch_all 1)`. The $lisp-cond landing gets the payload as the
  inner block's result, splits it into pseudo-locals (`__uc_cond$N` / `__uc_msg$N`) and compiles
  `(%warn (%string-concat "Unhandled condition: " (if cond (%condition-report-str cond) msg)))`,
  each half guarded by `(let ((v ...)) (if v v ""))` so a nil never renders as `NIL`. `%warn` is
  the existing fd-2 writer (exempt from the lazy-message narrowing), so no new RUNTIME reaches the
  module. **It is a FIFTH producer of the reserved `*error-output*` handle** -- one the compiler
  SYNTHESIZES in pass 2, invisible to any scan of user source -- so `--component --optimize` pruned
  `wasi:cli/stderr` out from under it and the report became the trap it precedes.
  `WasmUncaughtReportCompiler.emittedFor(ehMode)` is now the ONE predicate: `WasmLispCompiler`
  gates the pad on it and ORs the same value into `WasmComponentBuilder.Narrowing`'s
  `reachesStandardError` (`.kb/standard-output-redirect.md`). Then `unreachable`: the exit CLASS
  every host and test expects is the trap.
  - The try_table's own `end` restores a reachable, empty stack while `block $cond` owes an
    `eqref`, so an `unreachable` sits between them.
  - **Export wrappers keep the catch_all-only landing** (`WasmEmitHelper`): a host call's failure
    is the host's to report. Their artifacts are unchanged.
- **Outside EH mode nothing changes, byte for byte**: `%error` is a bare `unreachable` that
  evaluates none of its arguments and there is no tag section. Reporting there means turning EH
  mode on for every program (121,572 -> 175,486 B on the two-line toy). Verified by a stash dance
  over hello_world / pi_approx / a plain-`error` toy / a typed-condition-but-no-catching-form toy
  in plain / `--optimize=size` / `--component` / `--component --optimize=size` / `--dynamic` /
  `--no-gc` builds. `--no-gc` is exempt outright; a `--no-wasi` reactor compiles the pad but writes
  it into the discarding `fd_write` sink, which is why `doc/{en,ja}/guides/wasm-gc-module.md` still
  says a load-time failure there is a bare `RuntimeError: unreachable`.

**Size on `size-report/programs/zlib` `--optimize=size`** (worst case: an EH-mode program that
DEFINES condition classes and never binds one): before 72,837 B; `condMessagesObservable` forced on
76,812 (+3,975); routing gate broad as well 85,391 (+12,554, +17.2%). Taken deliberately -- with
only the first step a corrupt input printed `Unhandled condition: ` and nothing else.
**Re-evaluate if** the report renderer becomes narrowable to the classes that can actually ESCAPE.

Pinned cross-backend by `ci-spec.yaml`'s `standalone:` list -- a section `CiSpecE2eTest` runs one
program at a time, per backend, asserting stdout, the stderr lines that must APPEAR (wasmtime wraps
its own backtrace around ours) and a non-zero exit; the corpus cannot host these since running one
ends the program. Per backend by `RontoLispCliTest.anUncaughtConditionReportsOneLineAndExitsOne` /
`aRontolispDiagnosticIsNotDressedUpAsACondition`,
`JvmLispCompilerTest.anUncaughtConditionReportsOneLineAndRethrowsWithoutATrace`, and
`WasmLispCompilerIntegrationTest`'s `ehUncaughtPlainErrorReportsItsMessageBeforeTrapping` /
`ehUncaughtTypedConditionReportsThroughItsReportLambda` /
`anUncaughtConditionOutsideEhModeStaysSilent`.

## cerror + signal-operator function values
`cerror` has TWO lowerings, selected by the restart-mode gate. Outside restart mode:
`(cerror continue-format datum args...)` -> `(error datum args...)`
(`LispMacroExpander.expandCerror(cons, registry)`, control dropped) -- behavior-identical, since
with no restart runtime nothing could invoke a `continue` restart. In restart mode
`expandCerror(cons, registry, true)` emits the REAL
`(restart-case (error datum args...) (continue () :report continue-format nil))`. Dispatched in
the evaluator and both compilers like `error`; in `PackageRegistry.CL_MACROS`.

`error`/`signal`/`warn`/`cerror` also have FUNCTION values (cl-base64 signals via
`(apply #'error (list 'bad-base64-character :input ...))`):
- **Interpreter**: `LispEvaluator.registerEval` defines real functions that rebuild the literal
  call from the evaluated arguments (`rebuildSignalForm`: self-evaluating values stay literal,
  symbols/conses are quoted) and re-enter `eval`. `resolveFunction` checks the function namespace
  BEFORE the macro/special-operator guard. A NON-literal `error` datum evaluating to a SYMBOL
  re-dispatches as a condition-type designator (`expandError`'s `runtimeTypeDispatch` flag).
- **Compiled**: `BuiltinFunctionWrappers.SIGNAL_FUNCTIONS` wrappers, injected ONLY when the program
  contains a literal `(function op)` reference (`referencesFunctionValue` gate). LITE: the wrapper
  forwards the datum only -- a symbol datum signals a plain condition naming the class (the symbol
  case in `expandObjectSignal`), still caught by an `error` clause, but initargs/slots are dropped.

## Compiled runtime condition-type dispatch
A NON-literal `(error TYPE args...)` datum WITH initargs dispatches on the COMPILED backends too
(jzon's `%raise`, cl-postgres' `(error (get-error-type code) :code ...)`). It is NOT inlined at the
call site -- at 165 registered classes the inline per-class expansion reached 90 KB in one method,
past the JVM's 64 KB hard limit ([jvm-method-size-limits.md](jvm-method-size-limits.md)). The site
lowers to `(%error-runtime datum (list args...))`, and `expandTopLevelDefinitions` injects once per
program: one construction helper defun per registered CONDITION class (`%ERROR-RT-n` -- the same
`expandTypedSignal` a literal call gets, over `getf` reads of the runtime initarg list with each
slot's `:initform` as the getf default) plus the `%error-runtime` dispatch defun matching the datum
against both the qualified and (when unambiguous) plain spelling.

The dispatch is CHAINED (`%error-runtime` -> `%ER-1` -> ..., the `chainedDispatchDefuns` shape,
~600 cons nodes per segment): one `cond` lowers to nested `if`s on the JVM, so past ~140 condition
classes the outermost arm's else-branch overflowed the signed-16-bit branch encoding. One shared
shape on all four backends. Pinned by
`JvmLispCompilerTest#compileRuntimeErrorDispatchScalesPastTheBranchLimit` (200 classes, computed
dispatch at the END of the chain, RUN not just compiled).

A NON-condition class name and any non-symbol fall to the `expandObjectSignal` arm; the
interpreter's inline dispatch still constructs ANY class (a divergence only for
undefined-behavior programs). A DATUM-ONLY non-literal call keeps the object-designator path
(constructing a slot-less instance would run its `:report` over nil slots). Pinned by ci-spec
`runtime-type-dispatch-residue` and `runtime-type-dispatch-and-symbol-designators`,
`JvmLispCompilerTest#compileAndRunErrorWithComputedConditionType`, `JzonE2eTest`.

## Phase 4 -- handler-bind + the restart stack
**Invariant: the restart system is ONE shared Lisp-level lowering in `LispMacroExpander`,
identical on the interpreter, the JVM and both wasm-GC backends.** No backend has a per-form
compiler class; a divergence can only come from the primitives underneath (`catch`/`throw`,
`unwind-protect`, globals, closures), all pinned cross-backend. `--no-gc` keeps the lite lowering.

- **Two dynamic stacks, both TOP-LEVEL GLOBALS** (`%HANDLER-CLUSTERS%`, `%RESTART-CLUSTERS%`,
  injected as `defvar`s; plus `%HANDLERS-RAN%`, the completed-walk mark), mutated with plain `setq`
  and restored through an `unwind-protect` cleanup over a LEXICALLY saved value. **Deliberately NOT
  special-`let` rebindings**: the compile paths skip the special-binding restore on the
  error-throw, `catch`/`throw` and cross-lambda `return-from` channels
  (`.kb/dynamic-special-variables.md` limitation 2) while `unwind-protect` cleanups run on EVERY
  channel on EVERY backend, so a special binding would leak a handler cluster on exactly the path
  the feature exists for. **If you ever move these to `let`, the restore holes come back.**
- **The restart transfer rides `catch`/`throw`** with a FRESH cons as the tag (`(list '%restart)`),
  so tag identity is `eq` and cannot collide with a user tag -- which buys, already pinned:
  crossing function boundaries, running intervening `unwind-protect` cleanups, and passing through
  `handler-case` regions uncaught.
- **Clause bodies are compiled INLINE in the dispatch, never wrapped in a lambda** -- what makes
  postmodern's `transaction.lisp` shape work: the clause body `(go start)` targets a tagbody of the
  SAME function and stays a plain goto/br. A lambda wrapper would push every retry clause onto the
  cross-lambda `go` lowering.
- `restart-case` shape:
  `(let* ((tag (list '%restart)) (res (catch tag (let ((saved %restart-clusters%)) (unwind-protect (progn <push> (cons -1 (multiple-value-list <form>))) (setq %restart-clusters% saved)))))) (let ((idx (car res)) (args (cdr res))) <if idx = k then clause-k-with-args-bound ... else (values-list args)>))`.
  A restart record is the list `(%restart name invoker report interactive test)`; the invoker takes
  the argument LIST and is called with ONE fixed-arity `funcall` -- `apply` would drag the WASM
  eval runtime into every restart program.
- `handler-bind` pushes one cluster of `(type-test-closure . handler)` entries (the closure is
  `makeHandlerTypeTest`). `%run-handlers` walks the clusters and, per CLHS, rebinds the global to
  the REMAINING clusters while a cluster runs, so a handler that itself signals does not re-enter
  its own cluster (`handlerSignalingInsideHandlerDoesNotSeeOwnCluster`). A handler that returns
  declines and the walk continues.
- **The signal hook.** `expandError`/`expandWarn`/`expandSignalMacro` take a `signalHook` boolean;
  when set they insert `(%run-handlers <instance>)` BEFORE the `%error-cond`/`%signal-cond`/`%warn`
  terminal, so handlers run at the signal point with the signaling frame's restarts still
  established. Restart-mode `warn` is wrapped in a `muffle-warning` `restart-case`. **Every
  restart-mode signal terminal CARRIES the instance the hook just ran**: the string-designator
  error arm throws `%error-cond` instead of `%error`, and `expandObjectSignal`'s string/symbol arms
  bind their fresh instance (`__signal_inst`) and hand it to both `%run-handlers` and the terminal.
  That is the identity contract the guard below depends on, and it means a `handler-case` in
  restart mode catches the IDENTICAL instance (`handlerBindHandlerAndHandlerCaseSeeTheSameInstance`).

### Errors BUILT-INS raise run handler-bind handlers too
Rove's failure-recording model is `handler-bind` around USER code, so `(car 1)`, an out-of-range
`aref`, `(/ 1 0)` and an undefined function must reach the handlers.
- The `handler-bind` expansion wraps its body in the internal `(%hb-guard body)` landing pad,
  compiled per backend (`JvmHandlerCaseCompiler.compileGuard`,
  `WasmHandlerCaseCompiler.compileGuard`, `LispEvaluator.evalHbGuard`): a catch-any (JVM) /
  `$lisp-cond` (wasm) / `LispEvalException` (interpreter) region that synthesizes the
  `simple-error` of a condition-less throw, runs `%run-handlers` -- the FULL cluster stack from the
  innermost, CLHS rebinding included, so ONE pad run covers every enclosing cluster and outer pads
  skip by the mark -- and rethrows CARRYING the instance (JVM restores `_condTl`, wasm rebuilds the
  payload car, the interpreter attaches it). The pad never touches the hc-depth channel (`signal`
  under `handler-bind` still falls through to nil), has no cleanup (no `UnwindScope`, no
  trampoline), and does not catch the block-exit tag / rethrows a pending NLE.
- **Identity contract**: `%run-handlers` sets `%handlers-ran%` to its argument AT THE END of a
  completed walk, so a pad recognizes an already-walked condition by `eq` and handlers run ONCE.
  End-of-walk (not entry) marking keeps a nested signal inside a handler from clearing the outer
  condition's mark.
- **The interpreter ADDITIONALLY runs handlers at the SIGNAL POINT for built-ins**:
  `LispEvaluator.apply` wraps `builtIn.body().apply` and, on an escaping `LispEvalException` -- or a
  raw `IndexOutOfBounds` / `NegativeArraySize` / `Arithmetic` / `ClassCast`, wrapped into one first
  (`builtinFailureMessage` names the built-in when the raw message is letterless: `"make-array:
  -1"`) -- reuses or synthesizes the condition and runs the walk BEFORE unwinding, so restarts
  established below the handler-bind are still invocable. Errors raised outside that seam land in
  the pad instead. Zero cost until an exception escapes.
- **Deviations**: (1) on the COMPILED backends a raw error's handlers run at the `handler-bind`
  boundary -- intervening `unwind-protect` cleanups have run and restarts below it are gone (CL
  runs handlers first); a SIGNALED condition keeps exact signal-point semantics everywhere.
  (2) wasm-GC runs handlers only for `$lisp-cond` throws, so **a rove test whose body traps still
  ends a wasm run**, while on interpreter/JVM it becomes the recorded "Raise an error while
  testing." failure.
- Pinned by `handlerBindSeesTheErrorABuiltInRaises` / `handlerBindSeesAnUndefinedFunctionError` /
  `handlerBindRunsEachClusterOnceForABuiltInErrorInnermostFirst` /
  `arefOutOfBoundsAndNegativeMakeArrayAreCatchable` (evaluator), their `compileAndRun*` twins,
  `ehHandlerBindSeesASignaledErrorAndAnUndefinedFunctionError` /
  `ehHandlerBindRunsEachClusterOnceInnermostFirst` (wasm), and ci-spec `restart-system`.

### A `handler-case` joins the cluster stack, so it SHADOWS an enclosing `handler-bind`
**Invariant: CLHS 9.1.4.1 -- handlers run MOST RECENT FIRST and `handler-case` transfers control,
so a `handler-case` established inside a `handler-bind`'s extent handles the condition and the
enclosing handler-bind handler never runs.** (Only an outer handler that EXITS broke before -- and
that is what every test framework's failure recorder is.)

- `LispMacroExpander.handlerCaseProtectedForm` wraps the PROTECTED FORM (only it) in the same
  `let`-saved / `unwind-protect`-restored push `handler-bind` uses, pushing one cluster of
  `(type-test-closure . nil)` entries. **The nil cdr is the marker**: a nil handler is a
  handler-case clause, which HANDLES by transferring control, so `%run-handlers` stops its walk
  there (`__rh_stop`). The transfer is the ordinary throw the signal terminal performs immediately
  after, so no backend needed a new control path.
- **Wrapping only the protected form is what pops the cluster for the clause bodies**, so a clause
  body that signals is not caught by its own handler-case and reaches the enclosing handler-bind.
- **Three call sites = the three handler-case implementations** (`LispEvaluator.evalHandlerCase`,
  `JvmHandlerCaseCompiler.compile`, `WasmHandlerCaseCompiler.compile`), each passing its own
  restart-mode flag (`restartRuntimeLoaded` / `ctx.restartMode`). **Restart mode is the gate**: with
  no `handler-bind` anywhere there is no cluster stack to shadow, so every other program is
  byte-identical (`.kb/emitted-output-determinism.md`). `ignore-errors` inherits it.
- The compiled backends already suppressed the handlers when an inner handler-case caught first
  (their `%hb-guard` pad sits outside it); the interpreter's built-in seam sits BELOW the
  handler-case and ran them -- now its `%run-handlers` walk finds the handler-case cluster first and
  stops. (wasm-GC still cannot catch a raw TRAP at all.)
- Pinned by `anInnerHandlerCaseShadowsAnEnclosingHandlerBind` /
  `anInnerHandlerCaseWhoseClausesDoNotMatchStillLetsTheHandlerBindRun` /
  `aHandlerCaseClauseBodyDoesNotCatchWhatItSignals` /
  `anInnerHandlerCaseShadowsAnEnclosingHandlerBindForABuiltInError` (`LispEvaluatorTest`), their
  `compileAndRun*` twins, the `eh*` triple (no wasm twin for the built-in probe), and ci-spec
  `restart-system`.

### The restart-mode gate
**`LispMacroExpander.usesRestartSystem(program)`, computed on the SURFACE program** (the four
macros plus a call to / `#'` reference of a restart-runtime function). The scan matches those names
in OPERATOR POSITION of evaluated forms only: it recurses into sub-forms, never into spine cells,
skips `quote`d data, ignores keyword heads. The old spine-walking scan read ANY occurrence as an
operator, so chipz's bzip2 decoder, whose `tagbody` has a tag named `CONTINUE`, put every program
that loads chipz into restart mode (~7 KB on the zlib size-report row). A binding pair or clause
head spelling a restart name still over-approximates to true (the safe direction). Pinned by
`LispMacroExpanderTest.aNonOperatorRestartNameDoesNotFlipRestartMode` /
`anOperatorPositionRestartFormStillFlipsRestartMode`.

It must be computed before `expandTopLevelDefinitions` (which re-runs it to inject the runtime
defuns and the two globals) and threaded into: the JVM `blockExitChannel` / WASM `blockExitTag`
(the expansions ride `catch`/`throw`, and on WASM that also implies EH mode), `mayUseInstances`
(the hook constructs `simple-*` instances), and `Ctx.restartMode`. **All of these pre-scans run
before Pass 2, where the expansions happen, so none of them can see the expansion products -- that
is why the gate is a separate surface scan.** A program without a restart form keeps every one of
these off and stays byte-identical.

- **Trap**: the WASM chunked top level clones `Ctx` through `WasmAsyncEmit.freshCtx`, which
  enumerates flags EXPLICITLY. Without `restartMode` there, top-level chunks compiled the signal
  hook OFF while defun bodies had it ON, so a `handler-bind` at top level silently never ran its
  handlers. **Any future `Ctx` flag needs the same line.**
- **Interpreter**: no injection pass, so `ensureRestartRuntimeLoaded()` evaluates the same generated
  AST on the first restart-system form or the first resolution of a restart-runtime name (the
  `slotUnboundDefuns` precedent). The flag doubles as the signal-hook gate; the interpreter
  re-expands per evaluation so later signals pick the hook up.
- **`FreeVarAnalyzer` learned all four macros** (expand-before-walking); `handler-bind` uses
  `expandHandlerBindForAnalysis`, substituting `t` for the clause type tests so an unknown or
  compound type spec cannot reject the analysis.
- Lite deviations (on the doc pages): `&optional` clause parameters take nil rather than their
  default, no condition-restart association (the optional condition argument of
  `find-restart`/`compute-restarts` is ignored), restart records print as plain lists,
  `:report`/`:interactive` are stored but never rendered/run (no debugger --
  `break`/`*debugger-hook*` absent), `check-type`/`assert`/`ccase`/`ctypecase` offer no
  `store-value` restart.
- `use-value` / `store-value` share `LispMacroExpander.valueRestartDefun`: invoke the innermost
  restart of the same name with ONE value, nil when none is active. In
  `RESTART_RUNTIME_FUNCTION_NAMES` (so a bare call activates restart mode and the interpreter's
  lazy load) and `PackageRegistry.CL_FUNCTIONS`; doc pages
  `reference/functions/{use-value,store-value}.md`.
- Pinned by the restart block of `LispEvaluatorTest` / `JvmLispCompilerTest` / the
  `ehRestart*`/`ehHandlerBind*` block of `WasmLispCompilerIntegrationTest` (15-16 cases each:
  keyword restart invoked across functions, nested handler-bind layers, `find-restart` object +
  `(go start)` clause, 5-argument restart, `restart-bind`, `with-simple-restart`,
  `cerror`/`continue`, `muffle-warning`) and ci-spec `restart-system`. **That ci-spec case puts the
  whole concatenated program into restart mode**, so a hook regression shows up as an unrelated
  case failing.

### `signal` declines a handler-case no clause matches -- on every backend
**Invariant: CLHS 9.1.4.1 -- `signal` transfers control only to a handler that will handle the
condition. A `handler-case` whose clause types do not match is not applicable: the signal passes it
by, returns nil so the forms after it run, and the handler-case stays armed for a later matching
condition -- identically on all four backends.** The compiled backends used to approximate with the
handler-DEPTH counter alone and turned an unmatched decline into a top-level abort.

- The clause types ride the same cluster stack, now outside restart mode too:
  `handlerCaseProtectedForm` is called by both compiled emitters with
  `ctx.restartMode || ctx.signalClauseMatch`.
- **`%signal-cond` consults the stack**: `Jvm/WasmSignalCondCompiler` still require the depth
  channel to be positive FIRST (the depth is per thread of control while the cluster stack is a
  shared global), then call the injected `%hc-match-p` defun -- an iterative walk over
  `%handler-clusters%` testing nil-cdr entries only, because a handler-bind entry never transfers
  at `%signal-cond` -- and throw only on a match. A handler-case with only a `:no-error` clause
  pushes nothing and is therefore declined, which is also CL.
- **The gate is `LispMacroExpander.needsSignalClauseMatch(program)`**: the program contains BOTH a
  `signal` (operator position, or `#'signal`) AND a `handler-case`/`ignore-errors` head -- a
  surface scan with the operator-position discipline, computed by each compiler before
  `expandTopLevelDefinitions` (which re-runs it to inject `%hc-match-p`, prepend the
  `%handler-clusters%` defvar outside restart mode, and disable the no-definitions fast path). A
  program missing either half keeps the historical emission byte for byte; the interpreter is
  untouched. `WasmAsyncEmit.freshCtx` copies the flag.
- Known blind corner: a `signal` or catching form reachable only through a channel the surface scan
  cannot see (runtime `eval`, a computed designator forged from quoted data) keeps depth-only
  behavior.
- Handler ORDER in restart mode is unchanged; `%hc-match-p` only decides whether the terminal throw
  happens at all.
- Pinned by `signalFallsThroughAHandlerCaseWhoseClausesDoNotMatch` /
  `anUnmatchedSignalLeavesTheHandlerCaseArmedForALaterCondition` /
  `handlerBindHandlersStillRunWhenAnUnmatchedHandlerCaseDeclines` /
  `anErrorStillUnwindsThroughAnUnmatchedHandlerCase`, their `compileAndRun*` twins, the `eh*`
  quadruple, `LispMacroExpanderTest.needsSignalClauseMatchRequiresBothASignalAndACatchingForm`, and
  ci-spec `signal-declines-an-unmatched-handler-case`.

## A built-in error carries its CONDITION CLASS
`(handler-case (car 1) (type-error ...))` matches, and so does rove's
`(ok (signals (car 1) 'type-error))`. The class is decided where the failure is DETECTED, never by
pattern-matching the message at the catching end -- except for the failures the backends report as
bare text.

- **Interpreter**: `LispEvalException.ofClass(className, message)`;
  `LispEvaluator.synthesizeCondition` (the ONE synthesis point, shared by `handler-case` and
  `%hb-guard`) builds it through `ClosRegistry.newReportingCondition`, which nil-fills the layout
  and puts the message in `format-control`. Typed throw sites: the
  `car`/`cdr`/`first`/`rest`/`rplaca`/`rplacd` family, `Expected integer|number, got:`, the
  `fn: index out of bounds` check and `Division by zero` in `Environment`, plus the four
  `The variable X is unbound` / `The function X is undefined` sites. A raw Java failure escaping a
  built-in is classified by `LispEvaluator.rawFailureConditionClass` at the `apply` seam.
- **JVM**: the landing pad (`JvmHandlerCaseCompiler.emitSynthesizeCondition`, reached by both
  `handler-case` and `%hb-guard`) classifies the caught `Throwable`:
  `ClassCastException`/`IndexOutOfBoundsException` -> `type-error`; `ArithmeticException` ->
  `division-by-zero` when its message contains `ClosRegistry.DIVISION_BY_ZERO_MESSAGE_TOKEN` else
  `arithmetic-error`; and -- the message exceptions -- text starting `The variable ` /
  `The function ` and ending ` is unbound` / ` is undefined` -> its cell-error class, and text
  starting `Expected integer, got: ` / `Expected number, got: `
  (`ClosRegistry.EXPECTED_INTEGER|NUMBER_MESSAGE_PREFIX`, thrown by `_big`/`_dbl`) -> `type-error`,
  because those sites are plain `RuntimeException`s emitted in bytecode with no channel to carry a
  class (`JvmSymbolApiCompiler`, `JvmNumericRuntimeBuilder`). The arms are compiled Lisp forms built
  by `LispMacroExpander.reportingConditionForm`, so no slot index is baked here.
- **WASM**: the pad is unchanged, and correctly so -- only `$lisp-cond` throws land in it, either
  carrying a typed instance or a plain `%error` string (a `simple-error` on every backend); every
  RAW failure is a trap. **Two families diverge by CLASS rather than catchability**: an
  undefined-function call IS catchable here but as a `simple-error` (interpreter/JVM answer
  `undefined-function`), and a non-number reaching arithmetic IS catchable but as a `simple-error`
  (the other two answer `type-error`). **The stub cannot construct the typed instance**: it is
  produced during BODY compilation, after `mayCreateInstances` fixed whether the artifact has an
  instance representation and after `usedLayoutTags` chose which layouts to bake, so building one
  there is a gate/expansion disagreement (`%OBJ-NEW reached the compiler with no instance
  representation`). **Re-evaluation trigger**: teach both gates about undefined calls
  (`usedLayoutTags` already has the precedent, standing in for the `end-of-file` tag on the `read`
  family's presence). Pinned as a divergence by
  `WasmLispCompilerIntegrationTest.ehAnUndefinedFunctionCallIsCaughtAsASimpleErrorHere`.
- **Undefined functions keep the call-time stub contract**: a call to a name with no definition
  compiles to `The function X is undefined` at call time (plus a compile-time warning), matching
  the interpreter's late binding. It stays a STRING signal for the gate reason above.
- **The message a raw host failure reports is rontolisp's, not the host's**:
  `ClosRegistry.TYPE_ERROR_MESSAGE` replaces a `ClassCastException`'s Java class names (identically
  on interpreter and JVM) and `INDEX_OUT_OF_BOUNDS_MESSAGE` replaces the JVM's `Index 10 out of
  bounds for length 3`, whose length counts the layout cell in slot 0. Per-site texts a built-in
  writes itself (`car expects a cons cell, got: 1`) are kept and are NOT identical across backends
  (the JVM's `car` is a `checkcast` that cannot know its operator). **Re-evaluation trigger**: if a
  program needs the operator name in a compiled type error, fix it by per-operator emission at the
  check, not more message parsing at the pad. The substitution does NOT reach the UNCAUGHT
  top-level line on the JVM (`JvmUncaughtHandler` prints `ex.getMessage()` then rethrows THAT
  exception, whose message is final) -- deliberate.
- **Restart mode moves the undefined-function text out of the pad's reach**: with `handler-bind`
  anywhere, the string-datum `error` arm builds its `simple-error` at the SIGNAL point and hands it
  over on the condition channel, so `(handler-case (nosuchfn) (undefined-function ...))` matches on
  the JVM in a plain program and does not in a restart-mode one. RAW host failures are unaffected
  in both modes. **Re-evaluation trigger**: restart mode is exactly where both instance gates are
  already open (`mayUseInstances` forced on, `usedLayoutTags` null), so the stub CAN carry its class
  there -- do it in the compilers, which know the mode.
- **`conditionNarrowing` marks the five classes constructible**
  (`LispMacroExpander.rawFailureConditionClasses`): no site names them, but a pad can build one,
  and without the mark a caught `(car 1)` would print as a bare `#<TYPE-ERROR>`. Unlike the
  simple-* three it is CONDITIONAL on the program establishing a pad at all (`LANDING_PAD_HEADS` ->
  `ConditionTagScan.hasLandingPad`); marking them unconditionally cost the zlib program 680 B.

## A non-number reaching arithmetic signals a catchable type-error
**Invariant: a non-number operand reaching an arithmetic or comparison operator
(`+ - * / mod rem = < > <= >= min max abs gcd`, the bitwise family, `1+`/`1-`, and every float
coercion behind `sqrt`/`exp`/...) signals a CATCHABLE error carrying the interpreter's exact text
-- `Expected integer, got: <prin1>` on the exact path, `Expected number, got: <prin1>` on the float
path -- byte-identical on all four backends.** Prefixes in
`ClosRegistry.EXPECTED_INTEGER|NUMBER_MESSAGE_PREFIX`; detected at each backend's coercion FUNNEL,
never by wrapping operator sites.

- **Interpreter**: `Environment.asLong`/`asDouble`/`asBigInteger`, a `type-error` at the throw
  site, spelled from the shared constants. Which prefix a case sees depends on which dispatch arm
  the OTHER operands select -- `(+ 1 nil)` exact-path ("integer"), `(+ 1.5 nil)` float-path
  ("number") -- and the compiled funnels split the same way.
- **JVM** (`JvmNumericRuntimeBuilder`): `_big` (exact-path widening funnel) and `_dbl` (float
  funnel) test-and-throw `new RuntimeException(prefix + _lispToString(x))` where a bare `checkcast`
  used to let null through to a later NPE (`_abs`'s BigInteger arm routes through `_big` for the
  same reason). The pad (`emitRawFailureTest` case 0, `emitMessagePrefixHit`) classifies the two
  prefixes as `type-error`. One `instanceof` on each SLOW arm only; Long/Long and Double fast arms
  byte-identical; class grows ~1.4 KB.
- **wasm-GC (P1 and `--component`)**: `_int_val`'s non-integer arm calls `_type_err_int`,
  `_as_f64`'s non-number arm `_type_err_num` (`FUNC_TYPE_ERR_INT`/`FUNC_TYPE_ERR_NUM`, bodies
  `WasmEmitHelper.buildTypeErrBody`, signature `TYPE_PRINT_VAL` so no new type entry). In EH mode
  the body renders prefix + `_prin1_to_str` through `_string_concat` and throws the instance-less
  `(nil . message)` payload. **Outside EH mode both bodies are a bare `unreachable`** (no tag
  section; the "no reporting outside EH mode" decision stands) -- the failure stays a messageless
  trap, now `unreachable` instead of `cast failure`. The message prefixes are interned EARLY
  (`WasmLispCompiler`, beside `tSymEntry` -- **a string added during code emission lands after the
  data segment content is fixed and reads back as blanks**) and only in EH mode. `_int_val`'s
  limb-tier arm still TRAPS explicitly: `.kb/wasm-bignum.md`'s exact-or-trap boundary is about
  values that ARE integers. The `_as_f64` ladder was reordered float-first in the same change,
  turning the check's +9.8% float cost into a -21% win (`.kb/wasm-shared-coercion.md`). Size:
  +33-37 B per non-EH module, +283 B per EH module.
- **`--no-gc`**: unaffected, still traps.
- **What still traps on wasm-GC**: anything not funneled through `_int_val`/`_as_f64` -- `(car 5)`,
  division by zero, kinded/generic aref casts, the limb-tier boundaries.
- **The funnels' reach is wider than arithmetic**: a STORE into a packed float array goes through
  the same `_dbl` / `_as_f64`, so `(setf (aref #d(1.0 2.0 3.0) 0) "x")` moved with them (the JVM
  used to leak a raw `ClassCastException`). Pinned by `JvmFloatArrayTest`'s
  `nonRealStoreIsATypeError` / `singleNonRealStoreIsATypeError` -- the only two tests in the repo
  that noticed, and the reason to run the WHOLE suite after changing a shared runtime helper.
- **Class divergence, deliberate**: interpreter and JVM signal `type-error`; the wasm pair
  synthesizes `simple-error` (instance-less payload -- the undefined-function stub's gate problem
  exactly). **Re-evaluation trigger**: the same one; fix the two families together.
- Pre-existing edge unchanged: a condition thrown from INSIDE a wasm to-string capture leaves the
  capture flag set.

Pinned by `nonNumberArithmeticOperandsSignalCatchableTypeErrors` (evaluator), its
`compileAndRun*` twin (JVM),
`WasmLispCompilerIntegrationTest.ehNonNumberArithmeticOperandsAreCaughtWithTheInterpreterText` (+
`...OnTheComponentPathToo`, `ehANonNumberArithmeticOperandIsCaughtAsASimpleErrorHere`,
`ehAnUncaughtNonNumberOperandReportsTheInterpreterLineBeforeTrapping`,
`aNonNumberArithmeticOperandOutsideEhModeStaysATrap`), and ci-spec
`non-number-arithmetic-operands-are-catchable`.

## Pinned lists and tests
ci-spec has `condition-objects` (define-condition / make-condition / typecase / with-slots /
signal -> nil) and three catching cases (`handler-case-catches-typed-and-plain-errors` &c) --
their presence puts the whole concatenated program in EH mode, so `CiSpecE2eTest.runBackend`
passes `-W exceptions=y` to both wasmtime invocations. Behavior is pinned in `LispEvaluatorTest` /
`JvmLispCompilerTest` / the `eh*` tests of `WasmLispCompilerIntegrationTest` (`--no-gc`
compile-error pins in `NoGcWasmCompilerTest`). Argument-position shapes: the
`compileAndRunHandlerCaseIn*` block of `JvmLispCompilerTest` -- which must COMPILE, LOAD and RUN
the class, since the broken class was written without complaint and only failed at link time --
and ci-spec `handler-case-in-argument-position`. `ParseNumberE2eTest` expects the
`:report`-rendered `Invalid number: ...` message. ci-spec `condition-types` (the `cl` spelling from
a user package, a RUNTIME type specifier naming a seeded class, a typed built-in error) plus
per-backend `*ConditionTypeNamesAreClSymbols` / `*RuntimeTypeSpecifier*` /
`*BuiltInErrorCarriesItsConditionClass*` -- the interpreter's throw-site classes and the JVM pad's
exception classification are ONE rule pinned by the same-named pair.

## Out of scope (still)
The interactive debugger (`break`, `*debugger-hook*`, rendering a restart's `:report`, running its
`:interactive` function), condition-restart association, a `store-value` restart for
`check-type`/`assert`/`ccase`/`ctypecase` (the two `c`-operators are plain aliases of their
`e`-twins -- `expandCcase` -> `expandEcase`, `expandCtypecase` -> `expandEtypecase`; what is left
is the restart-mode split `cerror` already demonstrates), `--no-gc` catching (a scalar error-code
data path would be the shape; the GC path's `$lisp-cond` tag has no MVP equivalent, which is why
`--no-gc` rejects the catching forms outright rather than degrading), and the
special-`let`-restore-on-return compile-path limit (the restart stacks deliberately avoid it).

**Design note from the Phase 4 survey** (cl-postgres / Postmodern): shapes that need restarts
ESTABLISHED in one function and INVOKED from a handler running BEFORE unwinding, plus
`find-restart` returning a first-class restart object, mean handler-bind and the restart stack had
to land together -- **`restart-case` alone unblocks nothing real.** Verbatim cl-postgres needs no
restart system at all (its 4 `restart-case` sites are all `(restart-case (error X) (clauses...))`
with zero library-side invokers, so the lite lowering to the primary form is behavior-identical);
Postmodern proper is the real customer (`prepare.lisp:54-66` runs every `defprepared` under nested
`handler-bind`s that `(invoke-restart :reconnect)`; `transaction.lisp:63-70` exposes `find-restart`
+ `invoke-restart` as user API).
