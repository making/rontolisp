# Error handling: unwind-protect + condition objects + handler-case (todo-116, WASM catching todo-129)

The error-path foundation (Phases 1-3 of todo-116; Phase 4 —
`handler-bind`/`restart-case` — is not implemented). Backend contract:
**interpreter, JVM and the wasm-GC backends (Preview 1 + `--component`, incl.
serve) are full; only `--no-gc` rejects `unwind-protect` / `handler-case` /
`ignore-errors` at compile time** (its value model has no condition objects and
its contract is a zero-flag MVP module). The wasm-GC implementation (todo-129)
uses the WebAssembly exception-handling proposal and is gated: only a program
containing one of the three catching forms is compiled in "EH mode" (one
`$lisp-cond` tag, `try_table`/`throw`), and only such a program needs
`wasmtime -W exceptions=y` (37+) — anything else is byte-identical to a build
that never knew about EH. See "WASM (todo-129)" below. The condition-OBJECT
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
  (string → plain message, simple-* instance → slot 1, else
  `Condition of type X was signalled.`). A literal `(make-condition 'type ...)`
  argument re-routes through the typed path. `make-condition` builds instances
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
  binding a temp to the condition. A per-evaluator `ThreadLocal<Integer>
  handlerDepth` is incremented around the protected eval; `%signal-cond`
  raises only when it is positive, else returns nil (CL fall-through; LITE
  deviation: a raised signal that no established handler matches aborts
  instead of returning nil).
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

## cerror + signal-operator function values (todo-085, cl-base64)

`cerror` exists as a lite lowering: `(cerror continue-format datum args...)` ->
`(error datum args...)` (`LispMacroExpander.expandCerror`; no restarts, so the error is
not continuable and the continue format control is dropped). Dispatched in the evaluator
and both compilers like `error`; in `PackageRegistry.CL_MACROS` (pinned list-macros
updated).

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
(error type :format-control ...))` helper): `expandSignalDesignator` generates a
`member` cond over every registered class -- each branch is the same
`expandTypedSignal` a literal call would get (instance construction + :report
rendering + catchable class tag), matched against both the qualified and (when
unambiguous) plain spelling of the class name. Argument VALUE expressions are
bound to temps once (left to right); keyword LITERALS stay in place, because
`buildTypedConstruct`'s keyword-pair detection must see them (binding them broke
the slot layout and made jzon's :report read garbage -- a WASM-only trap, since
the JVM's bad `apply` was itself catchable while a wasm cast failure is not).
A DATUM-ONLY non-literal call keeps the object-designator path: it is the lite
`#'error` wrapper (datum forwarded without initargs -- constructing a slot-less
instance would run its :report over nil slots, cl-base64's
`bad-base64-character` regression) and the condition-object re-signal shape.
The `t` fallback arm is `expandObjectSignal` over the datum temp. Pinned by the
`runtime-type-dispatch-residue` ci-spec case and `JzonE2eTest`.

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

## Out of scope (Phase 4+ / future)

`handler-bind`, `restart-case`/`invoke-restart` (DEFERRED 2026-07-12 after the
source survey below), `muffle-warning`, `cerror` (undefined; a lite `cerror` →
`error` lowering is an M4/M5-sized item, see the survey), `break`, `--no-gc`
catching (a scalar error-code data path would be the shape if ever needed; the
GC path's `$lisp-cond` tag has no MVP equivalent, which is why `--no-gc` rejects
the catching forms outright rather than degrading), and the
special-`let`-restore-on-return compile-path limit.

### The Phase 4 survey (2026-07-12) — why restarts are deferred

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
