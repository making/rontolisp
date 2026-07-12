# Error handling: unwind-protect + condition objects + handler-case (todo-116)

The error-path foundation (Phases 1-3 of `.todo/116`; Phase 4 —
`handler-bind`/`restart-case` — is not implemented). Backend contract:
**interpreter and JVM are full; every WASM backend rejects `unwind-protect` /
`handler-case` / `ignore-errors` at compile time** (a WASM error is an
uncatchable trap; wasmtime gates the exception-handling proposal behind an
off-by-default flag), while the condition-OBJECT layer (`define-condition`,
`make-condition`, typed `error`/`warn`, `signal`-returns-nil, `with-slots`,
`typecase` on condition classes) compiles everywhere except `--no-gc`.

## Phase 1 — unwind-protect

- **Interpreter**: `LispEvaluator.evalUnwindProtect` = literal
  `try { eval protected } finally { eval cleanups }`. Both unwind channels are
  Java exceptions (`LispEvalException`, `LispReturnSignal`), so error AND
  `return`/`return-from` exits run the cleanup; a cleanup that itself signals
  replaces the pending unwind (CL: the newer exit wins — Java `finally`
  semantics exactly).
- **JVM**: `ByteCodeWriter.writeExceptionTable` +
  `ByteCodeWriter.ExceptionTableEntry` are the assembler additions (class
  version 50 verifies exception handlers WITHOUT a StackMapTable — pinned by
  `ByteCodeWriterTest.generateAndRun{TypedCatch,CatchAny}Handler`). Each
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

- A condition is a CLOS-subset tagged-list instance `(%class-<name> slots...)`.
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
  unknown class or non-keyword args → raw tagged list) and signals
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
- `FreeVarAnalyzer` learned `handler-case` (clause var is BOUND in the clause
  body), `ignore-errors` and `with-slots` — without this a lambda enclosing
  them mis-captures the bound variables.
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

## Pinned lists and tests

Adding operators changes `rontolisp:list-special-forms` (`unwind-protect`) and
`list-macros` (`signal`, `with-slots`, `handler-case`, `ignore-errors`) — those
lists are pinned in ci-spec (`rontolisp-package-introspection`), the three
per-backend unit suites AND the doc detail pages, all updated together. ci-spec
gained the cross-backend `condition-objects` case (define-condition /
make-condition / typecase / with-slots / signal → nil, valid on all four
backends); `handler-case`/`unwind-protect` behavior is pinned in
`LispEvaluatorTest` / `JvmLispCompilerTest` (WASM compile errors in
`WasmLispCompilerTest`/`NoGcWasmCompilerTest`) because the ci-spec driver
concatenates one program per backend and WASM would fail to compile them.
`ParseNumberE2eTest` now expects the `:report`-rendered `Invalid number: ...`
message (the stopgap `Condition ... was signalled.` pin was updated).

## Out of scope (Phase 4+ / future)

`handler-bind`, `restart-case`/`invoke-restart` (cl-postgres M3 tail —
`.todo/116` Phase 4), `muffle-warning`, `cerror`, `break`, WASM catching
(revisit when wasmtime enables the EH proposal by default), and the
special-`let`-restore-on-return compile-path limit.
