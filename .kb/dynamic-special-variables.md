# Dynamic (special) variable binding

Common Lisp's second binding discipline (alongside lexical): a variable proclaimed
*special* is bound with **dynamic extent** by `let`/`let*`/`progv` -- the binding
is visible to every function called during the body (not just lexically nested
code) and restored on exit. rontolisp implements this via **shallow binding**: a
special's value lives in its ordinary global cell, and a dynamic binding is a
save/set/restore over that cell -- on the interpreter and the JVM backend the
cell a binding writes is THREAD-scoped (see the per-backend sections), so
concurrent HTTP-handler requests do not clobber each other. Landed 2026-07-06
(todo-084); JVM thread-scoping 2026-07-27. Reference doc:
`doc/en/guides/missing-features.md` ("Dynamic (special) variable binding"),
`doc/en/reference/special-forms/{progv,let,defvar,defparameter}.md`.

## What proclaims a name special

`SpecialVarCollector` (`am.ik.rontolisp`, shared AST layer so the interpreter --
which must not depend on the `compiler` package -- can use it too) collects the
names: `defvar`/`defparameter`/`defconstant`, plus `(declaim (special ...))` /
`(proclaim '(special ...))`. `LispNames.SPECIAL` is the `special` declaration
identifier (NOT registered as a cl symbol -- it only appears inside a declaration
specifier, and registering it would perturb the pinned introspection counts). The
earmuffs (`*x*`) are a style hint, not the mechanism. Local `(declare (special
x))` IS honored, PESSIMISTICALLY: `SpecialVarCollector.collectForm` recurses
into every form (skipping `quote`) and a name declared special anywhere becomes
special program-wide -- the same treatment a `declaim` would give it (cl-ppcre's
convert phase threads its whole state through let-bound locally-declared
specials). The `declare` head and the `special` clause head are matched
package-insensitively (`splitQualified`): neither is a registered cl symbol, so
under `(in-package p)` the resolver spells them `p::declare`/`p::special`. The
interpreter collects at the top-level `eval(expr)` entry, BEFORE evaluating, so
a defun's local declares take effect for everything defined after it. Because
symbol reads consult the dynamic store BEFORE the lexical chain, a lambda or
macro parameter whose name is special must ALSO bind dynamically
(`LispEvaluator.apply`/`expandUserMacro` push/pop `DynamicBindings` for such
params) -- otherwise an active outer dynamic binding shadows the parameter. A
special is always ALSO a global (its default value when unbound); on the
compile path specials are unioned into the `GlobalVarCollector` set so each
gets a backing store.

## Interpreter (`LispEvaluator`) -- full fidelity, thread-scoped

- `DynamicBindings` (`eval` pkg): a per-evaluator `ThreadLocal<Map<String,
  Deque<LispVal>>>` -- per-name value stacks, thread-scoped. **Thread-scoping is
  the point**: the HTTP handler serves one virtual thread per request sharing the
  single `globalEnv`, so concurrent requests must not clobber each other's
  dynamic bindings (`LispEvaluatorTest.specialVariablesAreThreadScoped`).
- `specialVars` = `ConcurrentHashMap.newKeySet()` on the evaluator, filled by
  `evalDefvar` and the `declaim`/`proclaim` cases (via `SpecialVarCollector`).
- `evalLet`: two-phase when `specialVars` is non-empty (evaluate ALL inits in the
  outer env first -- parallel `let` -- then push specials / bind lexicals),
  `finally` pops. `let*` reuses `evalLet` via `expandLetStar` (nested single-binding
  lets). A fast lexical-only path (no allocation, no `finally`) runs when
  `specialVars` is empty, so the pure-lexical common case is untouched.
- Read (`evalSymbolRef`), `setq`, `symbol-value`, `boundp` consult
  `DynamicBindings` first (gated on `!specialVars.isEmpty() || progvUsed` so the
  hot path skips `ThreadLocal.get` -- and the HashMap it would allocate -- when no
  special/progv is ever used).
- `evalProgv`: runtime-computed symbol/value lists; binds each dynamically (extra
  symbols -> nil), sets `progvUsed`. progv-bound names need not be declared special.
- Restore fires on EVERY exit: normal return, non-local exit (`LispReturnSignal`),
  error unwind (`LispEvalException`) -- all unchecked, so the `finally` runs.

## JVM (`JvmLetCompiler`) -- thread-scoped shallow binding (hybrid representation)

**Thread-scoped since 2026-07-27** (interpreter parity; before that the binding
was shallow save/restore over the one process-global static field, so two
HTTP-handler requests binding the same special clobbered each other -- the
real-world bite was cl-postgres' `initiate-connection` binding
`*connection-params*`: concurrent `open-database` calls filled each other's
hash table and died on `(string= (gethash "integer_datetimes" ...) "on")` with
a null; 11 of 12 concurrent requests answered 500).

The representation is **hybrid, paid only where the semantics need it**:

- A special that is NEVER dynamically bound keeps the bare `_g$*` static field;
  its reads stay a single `getstatic` and such programs compile byte-identically
  to the pre-thread-scoped output.
- A special that IS bound somewhere -- decided by
  `SpecialVarCollector.collectDynamicallyBound`, which walks the fully expanded
  program for `let`/`let*` binding names, single-step-expanding built-in binding
  macros (`do`/`dolist`/`loop`/`multiple-value-bind`/`with-*`/...) via
  `LispMacroExpander.expandBuiltinMacro` so their generated lets are seen --
  additionally gets a `private static ThreadLocal _d$*` (created in `<clinit>`,
  never lazily: a racy first bind would mint two ThreadLocals) holding the
  thread's innermost binding as a one-element `Object[]` cell. A CELL, not the
  value: `nil` compiles to Java `null`, so the value itself cannot mark "no
  binding on this thread". Over-collection is only a small read cost;
  under-collection throws in `JvmLetCompiler` at compile time (never a silent
  process-global binding). Three shared helpers are emitted
  (`JvmDynVarRuntimeBuilder`): `_dget(tl, global)` (dynamic-first read),
  `_dbind(tl, v)` (push a binding, answer the previous cell), `_dset(tl, v)`
  (write the active binding, answer 0 when none so the call site falls through
  to `putstatic _g$*`).

The pre-existing rules keep working on top of that store:

- `Ctx.specialVars` threaded from `SpecialVarCollector.collect` (unioned into
  `globals` in `JvmLispCompiler` so each special has a `_g$*` static field --
  the global default `_dget` falls back to); `Ctx.dynVars` carries the
  ThreadLocal fields + helper refs (null when no special is ever bound).
- A special binding in `let` is a **DUAL-BIND** (2026-07-19, the cl-ppcre
  compile-path work): compile init, `DUP`, `_dbind` old cell into a temp (the
  dynamic set a called function reads), AND store the same value into a lexical
  slot (boxed when captured). The lexical slot exists ONLY so a closure built in
  the body CAPTURES the entry value and can read it after the extent ended
  (cl-ppcre's `end-string`, read by matcher closures at match time).
- **Read rule (dynamic-first)**: in the binding method, a read of the special
  resolves through `_dget`, not the slot (`JvmExprCompiler.compileSpecialRead`)
  -- so a callee's rebinding/`setq` is visible (cl-ppcre's `starts-with`
  accumulation threads state through callees). Inside a closure the CAPTURE
  wins. A `setq` of a dual-bound name writes BOTH the slot/capture and the
  dynamic store (`JvmSetqCompiler.emitGlobalStore`); with no binding active on
  the thread the store lands in `_g$*` -- the CL rule that `setq` of a special
  assigns the current dynamic binding.
- Restore after the body is `GETSTATIC _d$*; ALOAD temp; ThreadLocal.set`
  (stack-neutral), and each binding is pushed on `Ctx.specialBindScopes`
  (`{tlField, saveSlot, blockDepth}`) so a `return`/`return-from` exiting an
  enclosing block ALSO restores it (`JvmReturnCompiler.emitExit`) -- without
  this the scan closure's named exit leaked `*reg-starts*` into the dynamic
  store and the next scan saw stale registers.
- `defvar` of a special is unchanged (writes the `_g$*` global default). `let*`
  -> nested lets. Non-special globals stay lexical under `let`
  (`JvmLispCompilerTest.lexicalGlobalLetStaysLexical`).
- A spawned thread does NOT inherit the spawner's bindings (plain ThreadLocal,
  like the interpreter's `DynamicBindings`): it reads the global default until
  it binds for itself. Pinned by
  `JvmLispCompilerTest.specialVarBindingIsThreadScoped` (Thread.start/join via
  `java:` interop, fully deterministic) and, with user code as the spawner
  since todo-227, `JvmThreadTest.spawnedThreadDoesNotInheritTheSpawnersDynamicBindings`.
  `rontolisp:make-thread`'s bindings alist is the supported way to hand a
  spawned thread dynamic bindings -- and when a program uses the thread
  primitives, EVERY special is forced into the dynamically-bound set (plus the
  three stream specials into `specialVars`) so the runtime `_dtl` name dispatch
  can bind any of them; see `.kb/threads.md`.

## WASM (`WasmLetCompiler`) -- shallow binding over the module global

**Deliberately NOT thread-scoped, unlike the interpreter and the JVM backend
above.** Reason for the divergence (re-evaluate if it stops holding): WASM has
no threads here -- a served component's concurrent tasks interleave on ONE
instance's single stack, never preempting inside a synchronous handler body, so
overlapping requests cannot observe each other's shallow binding; the
`.todo/189` reproduction ran 8/8 clean under both `wasmtime serve` and `wash
dev` while the pre-2026-07-27 JVM lost bindings. If the wasm backends ever gain
real threads (shared-memory threads proposal) or a host that suspends a handler
MID-extent (an `await` inside a special `let` in a served handler), this
divergence becomes the same bug the JVM had and the module global needs a
per-task store. This is NOT a claim that the wasm backends are concurrency-safe
in general -- see `.todo/190` for a wasmCloud concurrency trap with a different
cause.

Same shape as the pre-thread-scoped JVM design: `Ctx.specialVars`, specials
unioned into `globals` (module-level
`(mut (ref null eq))`). A special binding saves the global into a temp local
(`global.get; local.set`), sets it, dual-binds a lexical slot (cell-boxed when
captured), and restores after the body (`local.get; global.set`); the same
dynamic-first read rule, dual `setq` (`WasmSetqCompiler`) and
`Ctx.specialBindScopes` exit restores (`WasmReturnCompiler` direct-br path and
`WasmReturnFromCompiler`) apply. Same for the `--component` path (shared core
module). `--no-gc`
`NoGcWasmCompiler` has no globals and rejects `defvar`/`declaim` at top level
outright, so a special can never be declared there
(`NoGcWasmCompilerTest.rejectsSpecialVariableDeclaration`).

## Compile-path limitations (interpreter is unaffected)

1. **`progv` is interpreter-only** -- a clear compile error on JVM/WASM
   (`JvmExprCompiler`/`WasmExprCompiler` PROGV case). The bound symbols are
   runtime-computed, so the compiler cannot name the static fields / wasm globals
   to save/restore. Would need the name-indexable `_genv`/`GLOBAL_ENV` runtime
   store.
2. **Exit restores are covered for `return`/`return-from`** since 2026-07-19
   (`Ctx.specialBindScopes`, see above) -- for exits compiled as a DIRECT branch
   inside the binding function. Remaining holes (all tracked in
   `.todo/192`): a WASM plain `return` that ALSO crosses an
   `unwind-protect`/`handler-case` region goes through the trampoline cascade,
   which does not know the save slots and skips the restores; `go` across a
   special `let` does not restore either; an ERROR caught by a
   `handler-case` outside the `let` skips the restore too (measured 2026-07-27:
   `(handler-case (let ((*x* 2)) (error "boom")) (error (e) *x*))` answers 2 on
   the JVM and leaves 2 bound after the handler; on the JVM the leak lands in
   the THREAD's dynamic store, so a per-request virtual thread takes it to the
   grave -- before thread-scoping it leaked into the process global); and a
   `return-from` that crosses a LAMBDA boundary (the block-exit throw/catch
   lowering) skips the restore on the JVM and both wasm-GC backends (found
   2026-08-08; reproducer and the cl-ppcre scanner corruption it causes --
   stale `*reg-starts*` returned by any zero-register scan after a failing
   register-regex loop -- are in `.todo/192`, which also records why the fix
   needs a save STACK, not catch-site slot restores: the slots live in the
   thrower's dead frames). The interpreter's `finally` covers every exit.
3. **`symbol-value`/`boundp`/`eval` see the global default, not a dynamic binding,
   on the compile path.** Those read the `_genv`/`GLOBAL_ENV` eval mirror, which
   the shallow save/restore does not update (it touches only the JVM thread-local
   store / wasm global). Direct reads/`setq` of the special (the common case) are
   correct.
   Self-heals after the `let` (both stores agree again). Narrow: only bites a
   program that both dynamically binds a special AND introspects it via
   `symbol-value`/`eval` on a compiled backend.
4. **A lambda/defun parameter named like a special is still lexical** (both
   interpreter and compilers). Naming a parameter with a special name and expecting
   the parameter binding to be dynamic is unsupported (rare).

## Introspection

`progv` is in `PackageRegistry.CL_SPECIAL_FORMS`, so `list-special-forms` includes
it on all backends (pinned in `ci-spec.yaml`, the three backend tests, and
`doc/*/reference/functions/rontolisp-list-special-forms.md`). It sorts between
`progn` and `quote`.

## Relationship to the two hand-rolled precedents

- **`*package*` load scoping stays separate** -- a conscious decision.
  `*package*` is resolved at read/compile time by `PackageResolver`
  (`pushPackage`/`popPackage` + `%push-package`/`%pop-package` markers), NOT as a
  runtime variable, so the runtime special-binding mechanism here does not cover
  it. Two distinct models (runtime specials vs compile-time resolver state); see
  `.kb/packages.md`.
- **Macro-time setf replay stays separate too.** cl-who reads `*html-mode*` at
  macro-EXPANSION (compile) time; a runtime special binding is invisible to an
  already-expanded macro, so `UserMacroExpander` replays a top-level `(setf (PLACE)
  ...)` into its macro-time evaluator. The decision to replay is a **static purity
  judgment** (`isPureConfigSetf`/`isPure`): a pure config setter -- one that only
  assigns special/global variables via a side-effect-free allow-list -- is
  auto-detected and replayed, deny-by-default so no external effect double-runs.
  It is a "macro-time configuration" concern, orthogonal to runtime dynamic
  binding. Details: `.kb/asdf.md` (cl-who paragraph).
- **A macro-time global's VALUE is demand-driven; its SPECIAL proclamation is
  not.** `LispEvaluator.registerLazyGlobal` adds the name to `specialVars` at
  once but parks the value expression as a thunk
  (`Environment.defineLazy`), so a `defvar` nobody reads at expansion time never
  runs its init there. Two couplings hold the replay together and both fail
  SILENTLY (wrong value, not a crash) if broken: `Environment.isBound` must count
  a pending thunk -- `isGlobalOrSpecialVariable` is half of the purity walk's
  "is this config state" test, and `defvar` idempotence rides on it -- and
  `Environment.set` must DISCARD a pending thunk, or the replayed
  `(setf *html-mode* :html5)` is later overwritten by the original `defvar`
  default when something forces the stale expression.

## Tests

`LispEvaluatorTest` (the `specialVar*`/`progv*`/`defparameter`/`declaim`/`proclaim`
/thread-scoped group), `JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest`
(the `specialVar*` group + `progvIsRejectedOn*`; the JVM group includes
`specialVarBindingIsThreadScoped` -- deterministic Thread.start/join via `java:`
interop -- and `specialVarSetqOutsideAnyBindingReachesTheGlobal`),
`NoGcWasmCompilerTest` (`rejectsSpecialVariableDeclaration`), and the
`special-variable-dynamic-binding` ci-spec case (all four backends).
