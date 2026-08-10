# Error handling: unwind-protect + condition objects + handler-case + restarts (todo-116, WASM catching todo-129, restarts todo-196)

The error-path foundation (Phases 1-4 of todo-116; Phase 4 —
`handler-bind` + the restart stack — shipped 2026-07-29, see "Phase 4" below).
Backend contract:
**interpreter, JVM and the wasm-GC backends (Preview 1 + `--component`, incl.
serve) are full; only `--no-gc` rejects `unwind-protect` / `handler-case` /
`ignore-errors` at compile time** (its value model has no condition objects and
its contract is a zero-flag MVP module). The wasm-GC implementation (todo-129)
uses the WebAssembly exception-handling proposal and is gated: only a program
containing one of the three catching forms is compiled in "EH mode" (one
`$lisp-cond` tag, `try_table`/`throw`), and only such a program needs
`wasmtime -W exceptions=y` (37+) — anything else is byte-identical to a build
that never knew about EH. See "WASM (todo-129)" below. A NON-LOCAL EXIT is not a
condition and must pass through a `handler-case` uncaught while still running every
`unwind-protect` cleanup — that holds for a cross-lambda `return-from`/`go` and for
`catch`/`throw`, which share one exit channel; the mechanics (and the
`ctx.blockExitTag`/`blockExitChannel` gate this file's handler-case sections read) live in
`.kb/do-return-block.md`. The condition-OBJECT
layer (`define-condition`, `make-condition`, typed `error`/`warn`,
`signal`-returns-nil, `with-slots`, `typecase` on condition classes) compiles
everywhere except `--no-gc`.

## Phase 1 — unwind-protect

- **Interpreter**: `LispEvaluator.evalUnwindProtect` = literal
  `try { eval protected } finally { eval cleanups }`. Both unwind channels are
  Java exceptions (`LispEvalException`, `LispReturnSignal`), so error AND
  `return`/`return-from` exits run the cleanup; a cleanup that itself signals
  replaces the pending unwind (CL: the newer exit wins — Java `finally`
  semantics exactly).
- **JVM**: `ByteCodeWriter.writeExceptionTable` +
  `ByteCodeWriter.ExceptionTableEntry` are the assembler additions. The
  emitters stay frame-free (raw assembler output is still version-50 and
  verifies handlers without a StackMapTable — pinned by
  `ByteCodeWriterTest.generateAndRun{TypedCatch,CatchAny}Handler`); the
  handler-entry frames the shipped version-61 class needs are synthesized
  afterwards by the offline `StackMapAugmenter` pass
  ([stackmap-augmenter.md](stackmap-augmenter.md)). Each
  `JvmLispCompiler.Ctx` carries a per-method `exceptionTable`; the four
  method-writing sites (main/chunks/defuns/lambdas) emit it.
  `JvmUnwindProtectCompiler` lays out protected-region / cleanup+GOTO /
  catch-any-handler(store, cleanup, ATHROW). The `return` channel is a plain
  GOTO (`JvmReturnCompiler` → `BlockTarget.exitPatches`), so it would skip
  both cleanup copies: `Ctx.unwindScopes` (a stack of `UnwindScope`, the
  `nextFuncId` shared-state pattern) records active scopes and
  `JvmReturnCompiler` compiles every ESCAPED scope's cleanup forms inline
  before its GOTO (escaped = `scope.blockDepth >= blockTargets.size()`,
  innermost first). Those inlined ranges are recorded as `holes` that the
  scope's own exception entries exclude (a throw from an inlined cleanup must
  not re-enter its own handler; it still lands in OUTER handlers — the CL
  unwinding order). `JvmClassShaker` was already exception-table-aware.
- **with-* retrofit**: `expandWithOpenFile` / `expandWithOutputToString` /
  `expandWithInputFromString` / the three usocket `with-*` expansions take a
  `boolean unwindProtect` (default true); the WASM call sites pass `false` to
  keep the old close-after-body shape. So interpreter/JVM close on EVERY exit,
  WASM on normal exit only (documented per page).
- NOT done (known limit, unchanged): the compile-path special-`let` restore
  on a `return` across the binding (`.kb/dynamic-special-variables.md`).

## Phase 2 — condition objects

- A condition is a CLOS-subset instance, the one object value model of
  `.kb/instance-syntax.md` (built by `(%obj-new '%class-<name> slots...)`, printed
  `#<NAME :SLOT value ...>`, NOT a list).
  `ClosRegistry`'s constructor seeds the built-in hierarchy (`condition` >
  `serious-condition` > `error` > `simple-error` + `parse-error`,
  `type-error`, ..., `warning` > `simple-warning`; `simple-*` carry
  `format-control`/`format-arguments` slots). `define-condition` =
  `defineConditionToDefclass` → the ordinary `expandDefclass` machinery
  (top-level-only on the compile path, spliced by
  `expandTopLevelDefinitions`); `(:report x)` is registered in the registry
  (string or `(lambda (c s) ...)` AST), `:documentation` dropped. **Lite
  multiple parents**: the FIRST parent provides the slot layout; the rest join
  the ancestor set only (`registerExtraAncestors`, merged in `registerClass`)
  so `typep`/`handler-case` match through them (split-sequence's
  `(program-error simple-condition)` shape). `findClass` also resolves a
  package-qualified spelling to a class registered under the plain name (the
  resolver qualifies non-CL-symbol names like `pkg::program-error`).
- `error`/`warn`/`signal` share `expandSignalDesignator`
  (`LispMacroExpander`): string designator = the LEGACY `(%error message)`
  path, byte-identical output; quoted-type designator binds initarg temps,
  builds the instance via the registry slot layout (`buildTypedConstruct`;
  unknown class or non-keyword args → the values fill the layout positionally,
  and an unregistered type is an error, as in CL) and signals
  `(%error-cond instance message)` where the message is the `:report`
  rendering (string directly; lambda via `with-output-to-string` + `funcall`),
  a supplied `:format-control` for `simple-*`-style classes, or the legacy
  `Condition ~s was signalled.` shape; object designator dispatches at runtime
  (string → the control RENDERED over the call's remaining arguments, see the
  next bullet; simple-* instance → slot 1, else
  `Condition of type X was signalled.`). A literal `(make-condition 'type ...)`
  argument re-routes through the typed path.
- **A datum that is a STRING at run time is a format control, and the arguments
  after it are its format arguments** — CL says so whatever the datum's shape in
  the source, so `(let ((c "~a-~a")) (error c 1 2))` reports `1-2`, not `NIL-NIL`
  (todo-220; before it, `expandSignalDesignatorInner` handed the OBJECT
  designator the datum alone and the arguments were dropped on the floor,
  identically on all four backends). `expandObjectSignal` takes the argument-list
  form and its string arm renders `(%fmt-render datum (list args...))`; the three
  callers that can carry arguments feed it — the compile-path fallthrough, the
  interpreter's runtime-type-dispatch arm (whose symbol test never sees a string:
  the object expansion tests `stringp` first), and `%error-runtime`'s `t` clause,
  where the second argument is a format-argument list rather than an initarg
  plist precisely because the datum was a string. The renderer is injected for it
  by a fourth arm of the gate in `.kb/format.md`.
  - **The rendering is EAGER**, exactly as `expandStringSignal` renders a literal
    control at expansion time: the instance carries the rendered text in
    `format-control` and nil `format-arguments`. Storing the raw control plus the
    argument list would be closer to CLHS *and* would retire the double-render
    deviation below — but only on this path: the literal path renders without a
    renderer in the artifact, which is the whole reason there are two of them
    (`.kb/format.md`), so making it carry the pair would put the renderer into
    every program that signals `(error "literal ~a" x)`. One operator with two
    condition shapes is the worse trade. **Re-evaluate if** the renderer ever
    becomes free (tree-shakeable per directive, or the literal path stops being
    a concatenation): then both paths should store the pair and render lazily.
  - The argument forms are evaluated only on the string arm — a datum that turns
    out to be a condition instance or a type symbol has no format arguments — so
    every datum-only call and every non-string arm keeps its previous expansion
    byte for byte.
  - The lite `#'error`/`#'warn`/`#'signal`/`#'cerror` WRAPPERS still forward the
    datum only (`BuiltinFunctionWrappers.SIGNAL_FUNCTIONS`), so
    `(apply #'error c '(1 2))` drops the arguments on the compiled backends.
    That is the wrappers' documented datum-only lite semantics (initargs go the
    same way), not this defect. `make-condition` builds instances
  the same way (registry overload; the registry-less overload keeps the old
  string collapse for `--no-gc` and `macroexpand-1`).
- Channels: interpreter `LispEvalException` carries a nullable
  `condition()`; JVM `%error-cond` stores the instance into the emitted
  `private static ThreadLocal _condTl` and throws
  `RuntimeException(message)` — uncaught output identical to `%error`. The
  field + `<clinit>` (and the `_hcDepthTl` depth counter) are emitted only
  when used (`JvmLispCompiler.ConditionChannel`, shared per compilation via
  the builder). WASM: `%error-cond` traps like `%error` (arguments in the
  designator LET still compile; the message expression does not).
- `typep`-style tests: `makeTypeTest` gained a `ClosRegistry` parameter and a
  class branch (descendant-tag membership, `equal` on the car — WASM
  content-safe); `typecase`/`etypecase` thread the registry from every
  dispatcher. `with-slots` is a new read-only expansion (`let` over
  `slot-value`; assignment does NOT write back — no symbol macros).
  `parseDefclassSlot` now accepts (and drops) the `:documentation` slot
  option (CL-legal; cl-utilities uses it).

## Phase 3 — handler-case / ignore-errors

- Surface: `(handler-case expr (type ([var]) body...)... [(:no-error ([var])
  body...)])`; clause types are `makeHandlerTypeTest` = `makeTypeTest` with an
  exact-tag fallback for names the registry does not know. A condition-less
  throw (plain `%error`, raw runtime exception) is caught as a synthesized
  `simple-error` with the message in slot 1. No match → rethrow (outer
  handler-case may catch; the JVM rethrow RESTORES `_condTl` first so the
  outer handler sees the typed instance). `:no-error` runs on normal
  completion OUTSIDE the handler, at most one variable (primary value;
  multiple values are syntactic). `ignore-errors` = sugar expansion
  (`expandIgnoreErrors`) over `(error (c) (values nil c))`.
- **Interpreter** (`evalHandlerCase`): `try/catch (LispEvalException)` —
  `LispReturnSignal` passes through; clause tests eval'd against a child env
  binding a temp to the condition. A per-evaluator
  `ThreadLocal<ArrayDeque<List<LispVal>>> handlerCaseTypes` holds the clause
  TYPE SPECIFIERS of every established handler-case (pushed around the
  protected eval); `%signal-cond` raises only when some active clause type
  actually MATCHES the condition (`anyHandlerCaseMatches`, the same
  `makeHandlerTypeTest` the catch path applies), else returns nil — the CL
  contract: `signal` unwinds only to a handler that will handle it. The
  driving consumer is trivia level2 (todo-243), whose pattern expander
  signals its own wildcard/guard-pattern conditions inside USER handler-case
  bodies at macro-expansion time; under the old depth-counter approximation
  any active handler-case turned those into aborts. **DIVERGENCE, with its
  re-evaluation trigger**: the COMPILED backends keep the depth-counter
  approximation (`Jvm/WasmSignalCondCompiler` test `_hcDepthTl > 0`), so a
  runtime `(handler-case (signal 'x) (error () ...))` falls through to nil
  on the interpreter but unwinds (and, unmatched, aborts) compiled. Reason:
  matching at the signal point needs a runtime stack of per-handler-case
  clause-type tests, machinery the emitted depth channel does not carry —
  and trivia itself never needs it, because pattern expansion always runs on
  the (macro-time) interpreter for every backend. Trigger: a library that
  signals non-error conditions at RUN time under a handler-case for another
  type (progress/telemetry signaling); then teach the handler-case emitters
  to push a static clause-tag set and `%signal-cond` to consult it. Pinned
  by `LispEvaluatorTest#signalFallsThroughAHandlerCaseWhoseClausesDoNotMatch`
  (deliberately absent from ci-spec).
- **JVM** (`JvmHandlerCaseCompiler`): catch-any exception-table region over
  the protected expression (the unwind-protect machinery, holes included — a
  `return` inside the region decrements the depth through the
  `UnwindScope` cleanup channel, the internal `%hc-depth-dec` form); handler
  reads-and-clears `_condTl`, synthesizes the simple-error from
  `Throwable.getMessage()` (quote-framed) when empty, then compiles clause
  tests/bodies as ORDINARY Lisp forms over a pseudo-local
  (`ctx.locals.put("__hc_cond$<slot>", slot)` — the shadowed mapping is saved
  and restored). `JvmSignalCondCompiler` checks `_hcDepthTl` (null = 0) and
  either raises like `%error-cond` or yields nil. handler-case catches ANY
  `RuntimeException` on the JVM (so `(car 5)`-style runtime failures are
  catchable as `error`); the interpreter catches `LispEvalException` only —
  a small cross-backend divergence for interpreter-internal Java exceptions.
  **The operand-stack spill (the reason it works in an ARGUMENT position).**
  Unlike unwind-protect, whose handler path ends in ATHROW and never rejoins,
  the handler-case handler MERGES back into the normal path — and the JVM
  discards the operand stack when it enters a handler. So the two edges into
  the merge point disagree by exactly the operands the ENCLOSING form had
  already evaluated (`(print (list "x" (handler-case ...)))` reaches it with
  `[arrayref, arrayref, int]` live on one edge and nothing on the other), and
  the class does not verify. `Ctx.spillOperandStack()` therefore saves the
  live operands into fresh locals BEFORE the protected region and
  `Spill.restore` reloads them past the merge, so both edges arrive empty; a
  handler-case compiled as a statement spills nothing and stays byte-identical.
  What is live comes from `am.ik.jvm.OperandStack`, a typed model of the stack
  that `Ctx.emit`/`emitU2` feed as the method is emitted (it also supplies a
  real `max_stack`, and raises on a merge-point mismatch rather than writing an
  unverifiable class). An object under construction (`new`, pre-`<init>`) can
  never be spilled — the model tags it `Slot.UNINIT` and the compiler rejects
  it, which no emitter triggers today (the `error` throw shape binds its
  message to a local first). A `return` that escapes a spilled region cannot
  keep the block's operands on the stack either — it reloads them from the
  outermost escaped `SpillScope` (`JvmReturnCompiler.emitStackUnwind`).
- `FreeVarAnalyzer` learned `handler-case` (clause var is BOUND in the clause
  body), `ignore-errors` and `with-slots` — without this a lambda enclosing
  them mis-captures the bound variables.

## WASM (todo-129): wasm-GC catching via the exception-handling proposal

- **EH-mode gate** (`WasmLispCompiler.compile`): the program (post pre-passes,
  libraries already spliced) is scanned for
  `handler-case`/`ignore-errors`/`unwind-protect` head symbols. Only then: the
  tag section (id 13, between memory and global) with ONE tag `$lisp-cond`
  whose type reuses `TYPE_PRINT_VAL` (`((ref null eq)) -> ()`), the handler-
  depth global (a `(mut i32)` appended AFTER the user globals, index in
  `Ctx.ehDepthGlobalIndex`), the throw path and the entry wrappers. A program
  without the forms is byte-identical (stash-dance proven across P1 /
  component base / http-client / sockets / serve / --optimize / --dynamic /
  --no-wasi exports / --no-gc).
- **Throw path** (`WasmErrorCompiler`): in EH mode `%error` / `%error-cond`
  evaluate their arguments, build the payload cons
  `(condition-instance . message-string)` (instance = nil for plain `%error`)
  and `throw $lisp-cond`; outside EH mode they stay a bare `unreachable`
  without evaluating anything. `throw` is stack-polymorphic like
  `unreachable`, so call sites are unchanged.
- **Top-level trap shape** (`WasmEmitHelper.emitCatchAllPrologue/Epilogue`):
  in EH mode `_start`/`run` and every export wrapper body (incl. serve's
  `%http-dispatch`) run inside `block` + `try_table (catch_all)` whose landing
  is `unreachable`; the normal path `return`s from INSIDE the try_table (so no
  result blocktype is needed whatever the signature). An uncaught condition
  therefore still exits with the same `unreachable` trap as before.
- **handler-case** (`WasmHandlerCaseCompiler`, mirrors the JVM layout):
  `block $done (result ref null eq)` [+ optional return trampoline] +
  `block $h` + `try_table (catch $lisp-cond $h)`; landing splits the payload,
  synthesizes the `simple-error` when the instance is nil (the message is
  already a quote-framed Lisp string — no re-framing, unlike the JVM's
  `getMessage()` path), dispatches `makeHandlerTypeTest` tests and clause
  bodies as ordinary Lisp over the `__hc_cond$<slot>` pseudo-local, rethrows
  the ORIGINAL payload when no clause matches. `:no-error` runs on the normal
  path outside the region. The depth global is inc/dec'd around the region;
  `WasmSignalCondCompiler` throws only when it is positive (nil fall-through
  otherwise; outside EH mode it keeps the old evaluate-and-nil emission).
- **unwind-protect** (`WasmUnwindProtectCompiler`): `block $u (result
  exnref)` + `try_table (catch_all_ref $u)`; landing = cleanups over the
  exnref, `throw_ref` (a throw FROM a cleanup propagates outward — newer exit
  wins). Normal exit stashes the value, runs cleanups, `br $done`.
- **The return channel — exit trampolines** (`Ctx.unwindScopes` +
  `WasmReturnCompiler`): each protected region (unwind-protect AND
  handler-case, whose "cleanup" is the internal `%hc-depth-dec`) pushes a
  `WasmLispCompiler.UnwindScope{cleanupForms, blockDepth, trampolineDepth}`.
  A `return` whose target `%block` lies outside the innermost scope
  (`scope.blockDepth >= blockMarkers.size()`, the JVM test) branches to that
  scope's trampoline block — emitted lexically OUTSIDE the try_table, only
  when an enclosing `%block` exists — which runs the cleanups and cascades to
  the next escaped scope's trampoline or the target block (innermost first,
  the CL order). Because the trampoline is outside the try_table, a throw
  from a cleanup cannot re-enter its own handler: the structural equivalent
  of the JVM `holes` mechanism, with no bookkeeping.
- **Divergence** (documented on the doc pages): wasm-GC catches SIGNALED
  conditions only — runtime traps (`(car 5)`-style ref.cast failures, integer
  division by zero, `unreachable`) stay uncatchable and skip unwind-protect
  cleanups. The three-point spectrum: interpreter catches `LispEvalException`
  only, JVM catches any `RuntimeException`, wasm-GC catches `$lisp-cond`
  throws only.
- **Walkers**: `WasmTreeShaker.scanInstr` (shared by `WasmImportInjector`)
  knows `throw` (0x08, tag immediate), `throw_ref` (0x0A), `try_table` (0x1F,
  blocktype + catch-clause vector) and the `exnref` valtype (0x69); tags are
  their own index space so function renumbering is unaffected. P1 EH +
  `--optimize` compose (pinned in `WasmTreeShakerTest.shakesEhModeModules`).
- **Component path**: core-module-internal only — no component-level section
  changes, blobs untouched, `--emit-wit` output unchanged; the async lift
  needs no modification (spike-proven, run flags gain only `-W exceptions=y`).
- **V8 hosts** (playground / jco): wasm-EH with exnref is default-on in
  current V8 (Chrome 137+ / Node 24+); Node 22 needs
  `--experimental-wasm-exnref`. Gated emission keeps existing programs
  unaffected.
- **usocket typed conditions**: `usocket.lisp` defines the condition hierarchy
  (`socket-condition` with a `message` slot + echo `:report`, `socket-error`,
  `connection-refused-error`, ...) and wraps
  `socket-connect`/`socket-listen`/`socket-accept` bodies in the internal
  `(usocket::%usock-guard form)` — expanded per backend
  (`expandUsocketGuard`): interpreter/JVM = `handler-case` +
  `usocket::%usock-resignal` (re-signals as `usocket:socket-error` with the
  original message, so uncaught output is unchanged), WASM = pass-through
  (the shim source is parsed ONCE and cached for all backends, so the branch
  cannot be a reader feature). The Phase 3 acceptance
  `(handler-case (usocket:socket-connect "127.0.0.1" closed-port)
  (usocket:socket-error (e) :refused))` → `:refused` is pinned on interpreter
  + JVM. The re-signal always uses `socket-error` (subtypes defined but not
  auto-selected).

## The read family signals a TYPED end-of-file (todo-200)

`read-char` / `read-byte`, and `read-line` with an explicit non-nil `eof-error-p`,
signal the SEEDED `end-of-file` condition class -- not a plain error -- on all four
backends. That is what makes the shape every real CL lexer is written in,
`(handler-case (loop ... (read-char s) ...) (end-of-file (e) ...))`, terminate
(postmodern's `execute-file.lisp`, cl-postgres' `protocol.lisp:473` reconnect guard).
`ClosRegistry` seeds a `:report` for the class (`END_OF_FILE_MESSAGE`, `"end of file"`)
so the message an uncaught end of file prints is the same everywhere and no call site
has to invent one.

- **Interpreter**: `Environment.endOfFile()` throws a `LispEvalException` carrying
  `ClosRegistry.newEndOfFileCondition()` -- a static factory, because `Environment` has no
  registry in scope. It is sound precisely because the class is SEEDED (same slot-less
  layout in every registry) and `handler-case` dispatches on the instance TAG, not on
  layout identity.
- **Compiled backends**: one shared CALL-SITE lowering,
  `LispMacroExpander.expandReadEofSignal`, applied by `Jvm/WasmExprCompiler` -- the
  built-in is called with the backend's own `(nil nil)` eof parameters and the expansion
  tests the nil result. It is sound for exactly these three operators because a
  successful read answers a character / a string / an integer and never nil. The runtime
  helpers keep their old throw as a BACKSTOP only. Returns null (no lowering, byte-identical
  output) when the call cannot signal: a literally nil `eof-error-p`, or an omitted one on
  `read-line`, whose rontolisp default is nil by long-standing convention.
- Two gates this had to touch, both easy to miss: `mayCreateInstances` scans the SOURCE
  program, so the read family is listed in `constructsInstance` (peek-char under its own
  name, its eof-error-p one argument later); and `#'read-char`/`#'peek-char`/`#'read-byte`
  joined `BuiltinFunctionWrappers.REFERENCE_GATED_FUNCTIONS`, because their wrappers now
  construct a condition and were previously injected into EVERY program -- which the gate,
  scanning only the source, could not see coming.
- **The `--component` socket rewrite needed the same lowering under its ALIAS.**
  `WasmSocketsRewrite` maps a 0/1-argument `(read-char s)` to `(%io-read-char s)`, whose
  non-socket arm falls through to `rontolisp::%read-char-raw`; lowering only the public
  name would have left every sockets.lisp-splicing component with the OLD uncatchable
  `unreachable` trap at end of file -- which is what the ci-spec case caught, on the
  component leg only, after Preview 1 was already green. `WasmExprCompiler` therefore
  applies `expandReadEofSignal` to the `%read-char-raw`/`%read-byte-raw`/`%read-line-raw`
  aliases too, and `constructsInstance` lists their qualified spellings.
- **`read` is deliberately NOT in this family** and neither is the default `read-line`:
  both answer nil at end of input (a pre-existing rontolisp convention a great deal of
  code depends on), and `read`'s datum may legitimately BE nil, so the nil-result test
  the lowering relies on would be ambiguous there. `read` has no `eof-error-p` parameter
  at all.
- Pinned by `readCharEndOfFileIsCatchableAsEndOfFile` in all three per-backend suites
  (both the `end-of-file` and the `error` clause) and the `postmodern-language-incidentals`
  ci-spec case.

## A condition's `:report` is what PRINTS it, not just what signals it (todo-206)

**The invariant: the text a condition REPORTS has exactly one implementation,
`%condition-report-str`, and both the printer and the signal message go through
it.** `princ` / `princ-to-string` / `format ~A` of a condition instance answer its
report; `prin1` / `~S` keep the `#<TYPE :SLOT value ...>` instance syntax
(`.kb/instance-syntax.md`) — CLHS's escape-mode split, matching SBCL. Before this
the report was applied only inside `expandSignalDesignator`, to build the *message
string*, so the OBJECT kept the generic instance rendering and
`(format t "~a" e)` on a caught condition printed `#<MY-E :MSG boom>`; a
`simple-warning` printed its raw `~A`-bearing control string where the arguments
belonged (cl-postgres surfaces every server NOTICE that way).

- **It rides the `print-object` seam, it does not sit beside it**
  (`.kb/clos.md`, todo-199). `expandPrintObjectHook` now fires when the program
  defines a `print-object` method **or** can build a condition; the escape-off arm
  of `%print-object-str` becomes, in effect, `(if (%obj-p x) (or
  (%condition-report-str x) (%princ-to-string x)) (%princ-to-string x))`. A
  `print-object` method on a condition class therefore still wins, in BOTH escape
  modes, because the method route is tested first.
- **`%condition-report-str` answers nil when the value's class reports nothing**,
  and every caller supplies its own fallback. That is what makes the routing safe
  to bolt onto sites that predate it: an under-approximating gate, or a class the
  renderer does not cover, degrades to the pre-report text instead of failing.
- **The class partition** (`conditionReportGroups`): a class's report is its own
  `:report`, else the nearest ancestor's along the SLOT-LAYOUT parent chain
  (`define-condition`'s inheritance, which the signal path did NOT walk before —
  `(error 'sub-of-reporting-class)` used to print the legacy shape), else — when it
  carries `format-control`/`format-arguments` — CLHS's specified
  `simple-condition` report, `(apply #'format stream control arguments)`. The
  groups are keyed by report owner / slot-index pair, NOT one clause per class:
  cl-postgres registers 100+ condition classes and a per-class dispatch is the same
  90 KB-in-one-method trap the runtime type dispatch hit
  ([jvm-method-size-limits.md](jvm-method-size-limits.md)).
- **`%format-condition` renders through `%fmt-render`** — the shared runtime
  control renderer a computed `(format nil ctrl args)` and `#'format` use, so the
  supported directive set is identical on all four backends AND identical to the
  literal expansion's ([format.md](format.md)). It was a cut-down lambda
  (`~~ ~% ~a ~s ~d ~x ~c`, everything else emitted verbatim while still consuming
  its argument) until todo-216, which is why cl-postgres' `~@[` reports printed
  the directive and a stray `NIL`. A
  control that is a FUNCTION (legal per the standard) is called on the stream
  through FIXED-ARITY `funcall`s for 0-3 arguments: `apply` would drag the whole
  wasm eval runtime into every program that prints a condition. A nil control is no
  report and answers nil.
- **The gate is `mayCreateConditions(program, registry)`**, the CONDITION half of
  `mayCreateInstances` sharing its `constructsInstance` case split (plus
  `#'error`/`#'warn`/`#'cerror`, whose wrappers reach the object-designator
  expansion, and `%obj-new` restricted to `%class-` tags descending from
  `condition`). It is answered TWICE in `expandTopLevelDefinitions` — once on the
  source program, because a program whose only condition is the `simple-error` a
  `handler-case` synthesizes has no definition to splice and would take the
  no-definitions fast path, and once on the expanded program, where a
  `define-condition` has become a `%obj-new` constructor. The answer is recorded in
  `ClosRegistry.routesConditionReports()` rather than in a `Ctx` flag: the registry
  is already threaded through every expansion and both backends' `Ctx`, so this
  needs no `WasmAsyncEmit.freshCtx` line (the trap Phase 4's `restartMode` hit).
  A program that cannot build a condition is byte-identical — verified by the
  stash-dance on a defstruct program and a `(error "literal")` program.
- **The generated defuns must not contain the SYMBOL `with-output-to-string`.**
  The wasm-GC EH-mode gate (`programUsesEhForm`) scans the program for it — its
  interpreter/JVM expansion rides `unwind-protect` — and that scan runs AFTER
  `expandTopLevelDefinitions` splices these defuns in, so leaving the macro there
  forced EH mode, and the `wasmtime -W exceptions=y` flag, on every program that
  merely signals a typed condition. `renderedToString` therefore pre-expands it to
  the close-after-body shape; the pin that caught it is
  `WasmLispCompilerTest.typedErrorWithLambdaReportCompilesOutsideEhMode`.
- **The interpreter loads the same generated AST** (`ensureConditionReportRuntimeLoaded`)
  on the `slotUnboundDefuns`/restart-runtime precedent — but it also
  RE-loads whenever the registry it partitions has changed (a stamp over the class
  and report counts), because a `define-condition` can follow the first print. The
  condition forms are where the routing turns ON: before one of them is evaluated
  no condition value can exist, so every printing operator keeps its historical
  shape.
- Cost, recorded deliberately: a program that can build a condition grows by the
  renderer plus the runtime format defuns (~11.6 KB of wasm on a 300 KB module
  when the format renderer was the cut-down lambda; the shared renderer is bigger,
  and is injected exactly once per program).
  It is not removable by tree-shaking — the renderer is reachable from every print
  site — and it is the price of the feature; the gate is what keeps every other
  program at zero.
- **Lite, inherited from the seam**: the rewrite is per CALL FORM, so a condition
  reached through a FUNCTION VALUE (`(mapcar #'princ conditions)`) still gets the
  raw conversion, exactly as a `print-object` method does. A `~A` with a COMPUTED
  control string does route, because the renderer's `~a` arm is an ordinary
  `(princ-to-string ...)` form in the injected `format-render.lisp` defuns and gets
  rewritten with everything else.
- **Known lite deviation**: rontolisp's string-designator signal path renders the
  message EAGERLY -- a LITERAL control at expansion time, a RUNTIME one through
  `%fmt-render` at the signal point (Phase 2, todo-220) -- so a
  `handler-case`-synthesized `simple-error` carries an
  already-rendered message in `format-control`. Printing it renders it a second
  time, which is invisible unless the rendered text still contains a live directive
  (`(error "~a" "~a")` prints `NIL` where CL prints `~a`). Rendering unconditionally
  is the CLHS-specified report and keeps `~%`-bearing controls with no arguments
  correct, which is the commoner shape.
- Pinned by `conditionReport*`/`simpleConditionFamily*`/`warnRenders*`/
  `aRuntimeControlStringDatum*`/`aPrintObjectMethodStillWins*`/
  `aConditionWithNoReport*` in `LispEvaluatorTest`, their `compileAndRun*` twins
  in `JvmLispCompilerTest`, the `ehConditionReport*`/`ehRuntimeControlString*`
  block of `WasmLispCompilerIntegrationTest`, and the cross-backend ci-spec cases
  `condition-report-printing` + `signal-runtime-control-string`.

## The condition floor is narrowed to what the program can construct (todo-316)

The compile path shrinks the condition runtime along three axes; the interpreter
never narrows (its world stays open at run time), and every narrowing is
IMPOSSIBILITY-based -- a pruned arm/layout can only be reached by a value the
program cannot make -- so behavior is identical wherever a value can exist.

- **`conditionNarrowing`** (`LispMacroExpander`) scans the expanded program for
  the constructible `%class-` tag set + whether any site can hand
  `%format-condition` an UNRENDERED control. Tag sources: literal datums of the
  signal family (`error`/`warn`/`signal`/`cerror`/`make-condition`,
  `make-instance` of a condition class), literal-tag `%obj-new`, plus always the
  synthesized simple-* three. It BAILS to `none()` (no narrowing) on a computed
  datum, `eval`/`symbol-function`/`fdefinition`, an escaping `#'error`-family
  value or quoted designator in data, `--dynamic`, restart mode. `handler-case`/
  `handler-bind`/`case`-family clause HEADS are type specifiers / keys, not
  calls, and the generated `%error-runtime`/`%error-rt-*` defuns are exempt from
  the quoted-designator bail (their `(list 'error)` is message data; their
  `%obj-new` tags are still collected -- through them every dispatched class IS
  constructible). Name forgery from computed strings can still reach a pruned arm:
  same carve-out family as the pruner's, and the failure is the caller's fallback
  report text, never a lost signal.
- **`conditionReportGroups` filters by the tag set**: the seeded `end-of-file`/
  `unbound-slot`/`simple-type-error` arms (and their report strings) leave every
  artifact that cannot construct them.
- **`%format-condition` declines the renderer** when every possible control is a
  directive-free literal (or nil) with nil arguments -- the common case, because
  every string-datum signal site pre-renders its message (`formatMessagePieces`)
  and stores nil arguments. Only an explicit `:format-control` initarg (surface
  keyword check + the baked `:initform`/`:default-initargs` cons check inside
  generated constructors' `%obj-new`) forces it back. On zlib the declined
  renderer plus its transitive string machinery was **-61 KB**. One corner moves
  TOWARD CLHS: the double-render deviation above requires the renderer, so a
  declined artifact prints a rendered-once message whose text contains a live
  tilde verbatim where the interpreter still re-renders it -- reachable only when
  a format ARGUMENT's own text carries `~`. Re-evaluation trigger: if that
  divergence ever bites, render once EVERYWHERE (retire the deviation), not by
  un-declining.
- **`WasmInstanceLayouts.emit` takes a used-tag set** (`usedLayoutTags` in
  `WasmLispCompiler`): a `%class-`/`%struct-` layout ships only when its tag or
  bare name occurs as a symbol in the final program (plus the simple-* three the
  handler lowering synthesizes during Pass 2), with null (= bake all) under
  `--dynamic`, an embedded eval runtime, restart mode, subclass enumeration,
  `find-class`/`change-class`/`allocate-instance`/`symbol-function`/
  `fdefinition`. The JVM backend already interned per referenced tag
  (`LayoutPool`); this is the WASM twin. Note a `handler-case (error (e) ...)`
  clause keeps every error-descendant layout through its lowered ancestor tag
  list -- correct, those are testable.
- **`needsRuntimeErrorDispatch` no longer misreads handler clauses**:
  `(handler-case b (error (e) use...))` used to parse as `(error <computed> ...)`
  and bake the whole per-class construction runtime (`%error-runtime` +
  `%error-rt-*` for all 23 seeded classes) into EVERY handler-case artifact --
  the same misread class as `usesRestartSystem`'s tagbody-tag CONTINUE (todo
  315). With clause heads skipped (handler-case/handler-bind/case family), the
  89,138 B handler-case probe is **23,341 B**; a real computed datum
  (`(error which :name 'x)`) still splices the dispatch, four-backend verified.

Pinned by `LispMacroExpanderTest.conditionNarrowing*` /
`anExplicitFormatControlInitargForcesTheRenderer` /
`aComputedDatumMakesTheConditionSetUnknowable` /
`aDirectiveFreeLiteralFormatControlStillDeclinesTheRenderer`, and behaviorally by
the whole condition block of the three per-backend suites plus the ci-spec
condition cases, which now run against narrowed artifacts.

## cerror + signal-operator function values (todo-085, cl-base64)

`cerror` has TWO lowerings, selected by the restart-mode gate (Phase 4 above).
Outside restart mode it keeps the lite `(cerror continue-format datum args...)` ->
`(error datum args...)` collapse (`LispMacroExpander.expandCerror(cons, registry)`; the
continue format control is dropped) — behavior-identical there, because with no restart
runtime nothing could invoke a `continue` restart anyway, and it keeps such a program
byte-identical. In restart mode `expandCerror(cons, registry, true)` emits the REAL
`(restart-case (error datum args...) (continue () :report continue-format nil))`, so
`(continue)` resumes past it with nil. Dispatched in the evaluator and both compilers
like `error`; in `PackageRegistry.CL_MACROS` (pinned list-macros updated).

`error`/`signal`/`warn` (and `cerror`) also have FUNCTION values, because cl-base64
signals via `(apply #'error (list 'bad-base64-character :input ...))`:

- **Interpreter**: `LispEvaluator.registerEval` defines real functions that rebuild the
  literal call from the evaluated arguments (`rebuildSignalForm`: self-evaluating values
  stay literal, symbols/conses are quoted) and re-enter `eval` -- identical semantics to
  the lowered form, typed conditions with slots included. `resolveFunction` now checks
  the function namespace BEFORE the macro/special-operator guard so these resolve.
  Additionally, a NON-literal `error` datum that evaluates to a SYMBOL at runtime
  re-dispatches as a condition-type designator (`expandError`'s
  `runtimeTypeDispatch` flag re-enters through the `error` function value, so the
  type resolves against the class registry at signal time).
- **Compiled backends**: `BuiltinFunctionWrappers.SIGNAL_FUNCTIONS` wrappers, injected
  ONLY when the program contains a literal `(function op)` reference
  (`referencesFunctionValue` gate in `JvmLispCompiler`) so every other program stays
  byte-identical. LITE: the wrapper forwards the datum only -- a symbol datum signals a
  plain condition naming the class (the new symbol case in
  `LispMacroExpander.expandObjectSignal`), still caught by a `handler-case` `error`
  clause, but initargs/slots are dropped. Both compiled backends inject through
  `BuiltinFunctionWrappers.generate` (`JvmLispCompiler` + `WasmLispCompiler`), with the
  same `(function op)` gate in each.

## Compiled runtime condition-type dispatch (todo-146, jzon %raise)

A NON-literal `(error TYPE args...)` datum WITH initargs now dispatches on the
COMPILED backends too (jzon's `(defun %raise (type pos format &rest args)
(error type :format-control ...))` helper, cl-postgres' `(error
(get-error-type code) :code ...)`). Since todo-115 the dispatch is NOT inlined
at the call site — at 165 registered classes the inline per-class expansion
reached 90 KB in one method, past the JVM's 64 KB hard limit
([jvm-method-size-limits.md](jvm-method-size-limits.md)) — the site lowers to
`(%error-runtime datum (list args...))` and `expandTopLevelDefinitions` injects
once per program: one small construction helper defun per registered CONDITION
class (`%ERROR-RT-n` — the same `expandTypedSignal` a literal call would get:
instance construction + :report rendering + catchable class tag, over `getf`
reads of the runtime initarg list with each slot's `:initform` as the getf
default) and the `%error-runtime` dispatch defun matching the datum against
both the qualified and (when unambiguous) plain spelling. Since todo-247 the
dispatch is CHAINED (`%error-runtime` → `%ER-1` → ..., the
`chainedDispatchDefuns` shape, ~600 cons nodes per segment): one cond lowers
to nested `if`s on the JVM, so past ~140 condition classes the outermost arm's
else-branch overflowed the signed-16-bit branch encoding (todo-211; mito's
tree crossed the threshold). One shared shape on all four backends — only the
JVM has the hard limit, but a per-backend split would be a divergence with no
reason behind it. Pinned by
`JvmLispCompilerTest#compileRuntimeErrorDispatchScalesPastTheBranchLimit`
(200 classes, computed dispatch at the END of the chain, RUN not just
compiled). A NON-condition
class name (an invalid CL datum) and any non-symbol fall to the
`expandObjectSignal` arm; note the interpreter's inline dispatch still
constructs ANY class — a divergence only for undefined-behavior programs.
A DATUM-ONLY non-literal call keeps the object-designator path: it is the lite
`#'error` wrapper (datum forwarded without initargs -- constructing a slot-less
instance would run its :report over nil slots, cl-base64's
`bad-base64-character` regression) and the condition-object re-signal shape.
Pinned by the `runtime-type-dispatch-residue` and
`runtime-type-dispatch-and-symbol-designators` ci-spec cases,
`JvmLispCompilerTest#compileAndRunErrorWithComputedConditionType` and
`JzonE2eTest`.

## Phase 4 — handler-bind + the restart stack (todo-196)

**The invariant: the restart system is ONE shared Lisp-level lowering in
`LispMacroExpander`, identical on the interpreter, the JVM and both wasm-GC
backends.** No backend has a per-form compiler class for it; every backend
reaches the same expansions through its ordinary dispatch, so a divergence can
only come from a difference in the primitives underneath (`catch`/`throw`,
`unwind-protect`, globals, closures) — all of which are pinned cross-backend
already. `--no-gc` is the one exception and keeps the historical lite lowering.

- **Two dynamic stacks, both TOP-LEVEL GLOBALS** (`%HANDLER-CLUSTERS%`,
  `%RESTART-CLUSTERS%`, injected as `defvar`s), mutated with plain `setq` and
  restored through an `unwind-protect` cleanup over a LEXICALLY saved value.
  **They are deliberately NOT special-`let` rebindings.** The compile paths skip
  the special-binding restore on the error-throw, `catch`/`throw` and
  cross-lambda `return-from` unwind channels (`.todo/192`,
  `.kb/dynamic-special-variables.md` limitation 2), while `unwind-protect`
  cleanups run on EVERY channel on EVERY backend (pinned by the ci-spec
  `unwind-protect-cleanups-and-return-channel` and `catch-throw` cases). Using a
  special binding here would have leaked a handler cluster on exactly the path
  the feature exists for. **If you ever move these to `let`, the restore holes
  come back.**
- **The restart transfer rides `catch`/`throw`** with a FRESH cons as the tag
  (`(list '%restart)`), so tag identity is `eq` and cannot collide with a user
  tag. That buys, for free and already pinned: crossing function boundaries,
  running intervening `unwind-protect` cleanups, and passing through
  `handler-case` regions uncaught (`.kb/do-return-block.md`).
- **Clause bodies are compiled INLINE in the dispatch, never wrapped in a
  lambda.** That is what makes postmodern's `transaction.lisp` shape work: the
  clause body `(go start)` targets a tagbody of the SAME function, so it stays a
  plain goto/br. A lambda wrapper would push every retry clause onto the
  cross-lambda `go` lowering (`.kb/do-return-block.md`) — correct since
  `.todo/217`, but an EH region and a tagbody re-entry loop per `restart-case`
  instead of a jump.
- `restart-case` shape: `(let* ((tag (list '%restart)) (res (catch tag (let
  ((saved %restart-clusters%)) (unwind-protect (progn <push> (cons -1
  (multiple-value-list <form>))) (setq %restart-clusters% saved)))))) (let ((idx
  (car res)) (args (cdr res))) <if idx = k then clause-k-with-args-bound ... else
  (values-list args)>))`. Normal completion carries the primary form's values
  through `multiple-value-list`/`values-list`, so `(restart-case (values 1 2)
  ...)` still answers two values. A restart record is the list `(%restart name
  invoker report interactive test)`; the invoker takes the argument LIST and is
  called with ONE fixed-arity `funcall` — `apply` would drag the WASM eval
  runtime into every restart program (its gate is a surface scan).
- `handler-bind` pushes one cluster of `(type-test-closure . handler)` entries;
  the test closure is `makeHandlerTypeTest` over the condition, i.e. the same
  test a `handler-case` clause compiles to. `%run-handlers` walks the clusters
  and, per CLHS, rebinds the global to the REMAINING clusters while a cluster
  runs, so a handler that itself signals does not re-enter its own cluster
  (pinned: `handlerSignalingInsideHandlerDoesNotSeeOwnCluster` in all three
  suites). A handler that returns declines and the walk continues.
- **The signal hook.** `expandError`/`expandWarn`/`expandSignalMacro` take a
  `signalHook` boolean; when set they insert `(%run-handlers <instance>)` BEFORE
  the `%error-cond`/`%signal-cond`/`%warn` terminal, so handlers run at the
  signal point with the signaling frame's restarts still established. The
  string-designator arms synthesize the same `simple-*` instance a
  `handler-case` would. `cerror` in restart mode becomes real: `(restart-case
  (error ...) (continue () :report cfc nil))`. Restart-mode `warn` is wrapped in
  a `muffle-warning` `restart-case` so `(muffle-warning)` aborts the output.
- **The gate is `LispMacroExpander.usesRestartSystem(program)`, computed on the
  SURFACE program** (the four macros plus a call to / `#'` reference of a
  restart-runtime function). The scan matches those names in OPERATOR POSITION of
  evaluated forms only (todo-315): it recurses into sub-forms, never into the spine
  cells, skips `quote`d data, and ignores keyword heads -- the old spine-walking scan
  read ANY occurrence of a symbol as an operator, so chipz's bzip2 decoder, whose
  `tagbody` has a tag named `CONTINUE`, put every program that loads chipz into restart
  mode (~7 KB on the zlib size-report row: the runtime defuns plus the signal hook in
  every `error`/`warn` expansion, plus forced `usesInstances`/`blockExitTag`). A
  binding pair or clause head spelling a restart name still over-approximates to true
  (the safe direction); a computed designator forged from quoted data now fails loudly
  as an undefined function, the same carve-out the pruner documents. Pinned by
  `LispMacroExpanderTest.aNonOperatorRestartNameDoesNotFlipRestartMode` /
  `anOperatorPositionRestartFormStillFlipsRestartMode`. It must be computed before
  `expandTopLevelDefinitions` (which re-runs it to inject the runtime defuns and
  the two globals) and threaded into: the JVM `blockExitChannel` / WASM
  `blockExitTag` (the expansions ride `catch`/`throw`, and on WASM that also
  implies EH mode, which their `unwind-protect`s need), `mayUseInstances` (the
  hook constructs `simple-*` instances), and `Ctx.restartMode` (the signal hook +
  real `cerror`). **All of these pre-scans run before Pass 2, where the
  expansions actually happen, so none of them can see the expansion products —
  that is why the gate is a separate surface scan and not a consequence.** A
  program without a restart form keeps every one of these off and stays
  byte-identical.
  - The WASM chunked top level clones `Ctx` through `WasmAsyncEmit.freshCtx`,
    which enumerates the flags EXPLICITLY. `restartMode` had to be added there
    too: without it the top-level chunks compiled the signal hook OFF while
    defun bodies had it ON, so a `handler-bind` at top level silently never ran
    its handlers (measured, then fixed). Any future `Ctx` flag needs the same
    line.
- **Interpreter**: no injection pass, so `ensureRestartRuntimeLoaded()`
  evaluates the same generated AST on the first restart-system form or the first
  resolution of a restart-runtime name — the `slotUnboundDefuns` precedent. The
  flag doubles as the signal-hook gate: before the first restart form no handler
  can exist, so the historical expansions are behavior-identical, and the
  interpreter re-expands per evaluation so later signals pick the hook up.
- **`FreeVarAnalyzer` learned all four macros** (expand-before-walking).
  `handler-bind` uses `expandHandlerBindForAnalysis`, which substitutes `t` for
  the clause type tests so an unknown or compound type spec cannot reject the
  analysis; the variable structure is otherwise identical.
- Lite deviations (documented on the doc pages): `&optional` clause parameters
  take nil rather than their default, no condition-restart association (the
  optional condition argument of `find-restart`/`compute-restarts` is ignored),
  restart records print as plain lists, `:report`/`:interactive` are stored but
  never rendered/run (no debugger — `break`/`*debugger-hook*` remain absent), and
  `check-type`/`assert`/`ccase` still offer no `store-value` restart.
- `use-value` / `store-value` (todo-243) joined the restart-runtime defuns
  (`LispMacroExpander.valueRestartDefun`, one shape for both): invoke the
  innermost restart of the same name with ONE value, nil when none is active —
  the CL contract, same nil-tolerance as `continue`. trivia level2's guard
  lifting (`(use-value s2)` from a handler-bind handler against the expander's
  own `use-value` restart-case clause) is the driving consumer. In
  `RESTART_RUNTIME_FUNCTION_NAMES` (so a bare call activates restart mode and
  the interpreter's lazy load) and `PackageRegistry.CL_FUNCTIONS`; doc pages
  `reference/functions/{use-value,store-value}.md`.
- Pinned by the restart block of `LispEvaluatorTest` / `JvmLispCompilerTest` /
  the `ehRestart*`/`ehHandlerBind*` block of `WasmLispCompilerIntegrationTest`
  (15-16 cases each, mirroring the postmodern site survey: keyword restart
  invoked across functions, nested handler-bind layers, `find-restart` object +
  `(go start)` clause, 5-argument restart, `restart-bind`,
  `with-simple-restart`, `cerror`/`continue`, `muffle-warning`) and the
  cross-backend ci-spec `restart-system` case. **That ci-spec case puts the whole
  concatenated program into restart mode**, so every other case's expectations
  now also exercise the signal hook — a hook regression shows up as an unrelated
  case failing.

### Undefined functions keep the call-time stub contract

The same stub contract `handler-bind` used to use still covers UNDEFINED
functions: a call to a name with no definition compiles to `The function X is
undefined` at call time (plus a compile-time warning), matching the
interpreter's late binding — cl-postgres references `stream-error-stream` (a CL
condition accessor rontolisp does not provide) on an error path only.

## Pinned lists and tests

Adding operators changes `rontolisp:list-special-forms` (`unwind-protect`) and
`list-macros` (`signal`, `with-slots`, `handler-case`, `ignore-errors`) — those
lists are pinned in ci-spec (`rontolisp-package-introspection`), the three
per-backend unit suites AND the doc detail pages, all updated together. ci-spec
gained the cross-backend `condition-objects` case (define-condition /
make-condition / typecase / with-slots / signal → nil, valid on all four
backends) and, with todo-129, three catching cases
(`handler-case-catches-typed-and-plain-errors` &c) — their presence puts the
whole concatenated program in EH mode, so `CiSpecE2eTest.runBackend` passes
`-W exceptions=y` to both wasmtime invocations. Behavior is pinned in
`LispEvaluatorTest` / `JvmLispCompilerTest` / the `eh*` tests of
`WasmLispCompilerIntegrationTest` (`--no-gc` compile-error pins stay in
`NoGcWasmCompilerTest`). The argument-position shapes are pinned by the
`compileAndRunHandlerCaseIn*` block of `JvmLispCompilerTest` — which must
COMPILE, LOAD and RUN the class, since the broken class was written without
complaint and only failed at link time — and cross-backend by the ci-spec
`handler-case-in-argument-position` case.
`ParseNumberE2eTest` now expects the `:report`-rendered `Invalid number: ...`
message (the stopgap `Condition ... was signalled.` pin was updated).

## Out of scope (still)

The interactive debugger (`break`, `*debugger-hook*`, rendering a restart's
`:report`, running its `:interactive` function), condition-restart association,
a `store-value` restart for `check-type`/`assert`/`ccase`, `--no-gc` catching (a
scalar error-code data path would be the shape if ever needed; the GC path's
`$lisp-cond` tag has no MVP equivalent, which is why `--no-gc` rejects the
catching forms outright rather than degrading), and the
special-`let`-restore-on-return compile-path limit (`.todo/192` — note the
restart stacks deliberately avoid it, see Phase 4 above).

### The Phase 4 survey (2026-07-12) — the shapes Phase 4 had to satisfy

**Superseded 2026-07-29**: Phase 4 shipped (see above); this survey is kept
because it is the source-level justification for the design decisions the Phase
4 section refers back to (why `restart-case` alone was not enough, and which
postmodern shapes the tests mirror).

Surveyed the cached `~/.rontolisp/quicklisp/software/postmodern-20260101-git`
sources (cl-postgres v2026-01, the `.todo/115` target) for every real use of
`restart-case` / `handler-bind` / `invoke-restart` / `find-restart` / `signal` /
`cerror`. Conclusion: **the verbatim cl-postgres needs NO restart system — not
even a vendored no-retry patch** — because the existing lite `expandRestartCase`
(primary form only) is behavior-identical for it. The real gate is Postmodern
proper.

cl-postgres proper (the `.todo/115` M3-M5 target):

- `restart-case`: 4 sites, ALL the shape `(restart-case (error X) (clauses...))`
  — public.lisp:224 (`initiate-connection`'s `:reconnect`), public.lisp:311
  (`database-connection-lost` `:reconnect`), public.lisp:373
  (`with-reconnect-restart`'s retry flet around `exec-query` & friends),
  sql-string.lisp:29 (ratio-precision `continue` / `disable-assertion`). In CL
  those clauses run ONLY when user code invokes them via `handler-bind` +
  `invoke-restart`, and **cl-postgres itself never does** (zero library-side
  invokers). So the lite lowering to the primary form signals the same error the
  same way a real CL does when no handler invokes the restart; `handler-case`
  over `database-error` covers the whole query-round-trip error surface.
- `handler-bind`: exactly ONE real site — public.lisp:386,
  `wait-for-notification` (LISTEN/NOTIFY), catching `postgresql-notification`,
  which protocol.lisp:130 raises via `warn`. Not on the `exec-query` path.
  `handler-bind` is an undefined symbol here, so the file LOADS (defun bodies
  are lazy on the interpreter); only a call to `wait-for-notification` fails.
  LISTEN/NOTIFY is unsupported until Phase 4.
- `invoke-restart` / `find-restart`: ZERO real sites — the errors.lisp:142-147
  grep hits are inside a docstring, not code.
- `cerror`: 4 sites (protocol.lisp:269/289 auth edge + SCRAM signature
  validation, scram.lisp:216/267 input validation) — all abnormal paths, none
  reached by trust/password auth. The continue restart is never programmatically
  invoked, so a lite `cerror` → `error` lowering suffices and is an M4/M5 item,
  NOT a Phase 4 dependency.

Postmodern proper (out of `.todo/115` scope) is the REAL Phase 4 customer:
prepare.lisp:54-66 (`generate-prepared`) runs every `defprepared` under nested
`handler-bind`s that `(invoke-restart :reconnect)` on
`database-connection-error` / `admin-shutdown` and auto-reset on
`invalid-sql-statement-name` / `duplicate-prepared-statement` — the hot path of
the prepared-statement API; plus prepare.lisp:289, transaction.lisp:63-70
(`retry-transaction` exposes `find-restart` + `invoke-restart` as user API),
json-encoder.lisp:125/282-288, connect.lisp:58, roles.lisp:276,
execute-file.lisp:392.

The survey's one design addition: those Postmodern shapes need restarts
ESTABLISHED in one function and INVOKED from a handler running BEFORE unwinding,
plus `find-restart` returning a first-class restart object — i.e. handler-bind
and the restart stack must land together. **`restart-case` alone unblocks
nothing real.**
