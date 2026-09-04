# Dynamic (special) variable binding

A variable proclaimed *special* is bound with dynamic extent by `let`/`let*`/`progv`: visible
to every function called during the body and restored on exit. Implemented by SHALLOW BINDING
-- the value lives in the ordinary global cell and a binding is save/set/restore over it. On
the interpreter and the JVM that cell is THREAD-scoped, so concurrent HTTP-handler requests do
not clobber each other; WASM is not (see below). Docs:
`doc/en/guides/missing-features.md`, `doc/en/reference/special-forms/{progv,let,defvar,defparameter}.md`.

## What proclaims a name special

`SpecialVarCollector` (`am.ik.rontolisp`, the shared AST layer, so the interpreter -- which
must not depend on `compiler` -- can use it): `defvar`/`defparameter`/`defconstant`,
`(declaim (special ...))`, `(proclaim '(special ...))`. `LispNames.SPECIAL` is the declaration
identifier, NOT registered as a cl symbol (it only appears inside a declaration specifier, and
registering it would perturb pinned introspection counts). Earmuffs are style, not mechanism.

- Local `(declare (special x))` IS honored PESSIMISTICALLY: `collectForm` recurses into every
  form (skipping `quote`) and a name declared special anywhere is special program-wide (what
  cl-ppcre's convert phase needs).
- `declare` and `special` heads are matched package-insensitively (`splitQualified`): neither
  is a registered cl symbol, so under `(in-package p)` the resolver spells them
  `p::declare`/`p::special`.
- The interpreter collects at the top-level `eval(expr)` entry BEFORE evaluating, so a defun's
  local declares apply to everything defined after it.
- Since symbol reads consult the dynamic store BEFORE the lexical chain, a lambda or macro
  parameter whose name is special must ALSO bind dynamically (`LispEvaluator.apply` /
  `expandUserMacro` push/pop `DynamicBindings`), else an outer dynamic binding shadows it.
- A special is always ALSO a global (its unbound default); on the compile path specials are
  unioned into the `GlobalVarCollector` set so each has a backing store.

## Interpreter (`LispEvaluator`) -- full fidelity, thread-scoped

- `DynamicBindings` (`eval`): per-evaluator `ThreadLocal<Map<String, Deque<LispVal>>>`.
- `specialVars` = `ConcurrentHashMap.newKeySet()`, filled by `evalDefvar` and the
  `declaim`/`proclaim` cases.
- `evalLet`: two-phase when `specialVars` is non-empty (evaluate ALL inits in the outer env --
  parallel `let` -- then push specials / bind lexicals), `finally` pops. `let*` reuses it via
  `expandLetStar`. A fast lexical-only path (no allocation, no `finally`) runs when
  `specialVars` is empty.
- Read (`evalSymbolRef`), `setq`, `symbol-value`, `boundp` consult `DynamicBindings` first,
  gated on `!specialVars.isEmpty() || progvUsed` so the hot path skips `ThreadLocal.get`.
- `evalProgv`: runtime-computed symbol/value lists, extra symbols -> nil, sets `progvUsed`;
  progv-bound names need not be declared special.
- Restore fires on EVERY exit (normal, `BlockReturnSignal`, `LispEvalException`) -- all
  unchecked, so the `finally` runs.

## JVM (`JvmLetCompiler`) -- thread-scoped, hybrid representation

- A special NEVER dynamically bound keeps the bare `_g$*` static field; reads stay one
  `getstatic`.
- A special that IS bound somewhere -- decided by
  `SpecialVarCollector.collectDynamicallyBound`, which walks the fully expanded program for
  `let`/`let*` binding names, single-step-expanding built-in binding macros
  (`do`/`dolist`/`loop`/`multiple-value-bind`/`with-*`/...) via
  `LispMacroExpander.expandBuiltinMacro` so their generated lets are seen -- additionally gets
  a `private static ThreadLocal _d$*`, created in `<clinit>`, NEVER lazily (a racy first bind
  would mint two ThreadLocals), holding the thread's innermost binding as a one-element
  `Object[]` CELL. A cell, not the value: `nil` compiles to Java `null`, so the value cannot
  mark "no binding on this thread". Over-collection costs a read; under-collection throws in
  `JvmLetCompiler` at compile time (never a silent process-global binding).
- Helpers from `JvmDynVarRuntimeBuilder`: `_dget(tl, global)` (dynamic-first read),
  `_dbind(tl, v)` (push, answer previous cell), `_dset(tl, v)` (write active binding, answer 0
  when none so the call site falls through to `putstatic _g$*`).
- `Ctx.specialVars` from `SpecialVarCollector.collect` (unioned into `globals` in
  `JvmLispCompiler`); `Ctx.dynVars` carries the ThreadLocal fields + helper refs (null when no
  special is ever bound).
- A special binding in `let` is a DUAL-BIND: compile init, `DUP`, `_dbind` old cell into a
  temp, AND store the same value into a lexical slot (boxed when captured). The lexical slot
  exists ONLY so a closure built in the body captures the entry value and can read it after the
  extent ended (cl-ppcre's `end-string`).
- Read rule is DYNAMIC-FIRST: in the binding method a read resolves through `_dget`, not the
  slot (`JvmExprCompiler.compileSpecialRead`), so a callee's rebinding/`setq` is visible.
  Inside a closure the CAPTURE wins. A `setq` of a dual-bound name writes BOTH the
  slot/capture and the dynamic store (`JvmSetqCompiler.emitGlobalStore`); with no active
  binding it lands in `_g$*` (the CL rule).
- Restore after the body is `GETSTATIC _d$*; ALOAD temp; ThreadLocal.set` (stack-neutral), and
  each binding is pushed on `Ctx.specialBindScopes` (`{tlField, saveSlot, blockDepth}`) so a
  `return`/`return-from` out of an enclosing block also restores (`JvmReturnCompiler.emitExit`)
  -- without it cl-ppcre's scan closure leaked `*reg-starts*` and the next scan saw stale
  registers.
- `defvar` writes the `_g$*` global default; `let*` -> nested lets; non-special globals stay
  lexical under `let` (`JvmLispCompilerTest.lexicalGlobalLetStaysLexical`).
- A spawned thread does NOT inherit the spawner's bindings (plain ThreadLocal): it reads the
  global default until it binds for itself. `rontolisp:make-thread`'s bindings alist is the
  supported hand-over, and when a program uses the thread primitives EVERY special is forced
  into the dynamically-bound set (plus the three stream specials into `specialVars`) so the
  runtime `_dtl` name dispatch can bind any of them (`.kb/threads.md`).

## WASM (`WasmLetCompiler`) -- shallow binding over the module global

Deliberately NOT thread-scoped: WASM has no threads here, and a served component's concurrent
tasks interleave on ONE instance's single stack without preempting inside a synchronous handler
body. Re-evaluate if the wasm backends gain real threads or a host that suspends a handler
MID-extent. This is not a claim that the wasm backends are concurrency-safe in general.

That trigger already fired for JSPI: a `--no-wasi` reactor whose host answers an import through
`WebAssembly.Suspending` (`.kb/wasm-import.md`) suspends the wasm stack mid-call and may
re-enter the export before the first call resumes, so two overlapped calls read each other's
binding. The answer is the RE-ENTRY GUARD (`.kb/wasm-import.md`): every export wrapper of a
module that can suspend traps a second entry, re-establishing "one call at a time on one stack"
BY the module instead of assuming it of the host.

`--reentrant` relaxes the guard and brings a PER-TASK store with it
(`codegen.wasm/WasmDynVars`): only the specials `collectDynamicallyBound` names get a slot in a
per-call TASK RECORD -- a `TYPE_HASH_BUCKETS` array of nullable `TYPE_CELL`s in a module global
-- created by every export wrapper on entry, seeded by `_start` for the load path, and
saved/restored by the import wrapper around the suspending host call (the one point another
extent can run; a wrapper local survives the park). The JVM hybrid's rules carry over exactly:
dynamic-first reads with the module global as default, DUAL-BIND with the lexical slot for
captures, dual `setq`, `specialBindScopes` exit restores, over-collection a read cost and
under-collection a compile-time throw. Every non-reentrant module is byte-identical; a
reentrant module with no dynamically-bound special gains no task global.

Base shape otherwise: `Ctx.specialVars`, specials unioned into `globals` (module-level
`(mut (ref null eq))`); a binding saves the global into a temp local (`global.get; local.set`),
sets it, dual-binds a lexical slot (cell-boxed when captured), restores after the body
(`local.get; global.set`); same dynamic-first read, dual `setq` (`WasmSetqCompiler`) and
`Ctx.specialBindScopes` exit restores (`WasmReturnCompiler` direct-br path,
`WasmReturnFromCompiler`). Same for `--component` (shared core module). `--no-gc`
`NoGcWasmCompiler` has no globals and rejects `defvar`/`declaim` at top level outright
(`NoGcWasmCompilerTest.rejectsSpecialVariableDeclaration`).

## progv on the compile paths

`progv` compiles on the JVM and both WASM backends. Bound symbols are runtime values, but the
candidate SPECIALS are static -- that asymmetry is the design
(`LispMacroExpander.expandProgvForCompile`, shared by both compilers; the interpreter keeps
native `evalProgv`).

- Lowers to a loop over the runtime symbol list whose body dispatches each name through an
  `equal` chain over the program's special set; a matching arm is `(%progv-dyn-bind NAME value)`,
  a literal-name internal operator (`Jvm/WasmProgvCompiler`) emitting exactly the save-and-set
  the `let` path emits (JVM: `_dbind` over `_d$`; WASM: the module global, or the per-task slot
  under `--reentrant`, `WasmDynVars.emitProgvBind`). Previous binding state flows as a VALUE
  consed onto a save list -- bind and restore sit in different loop iterations, so a save slot
  cannot work.
- `collectDynamicallyBound` returns EVERY special of a progv-using program (the make-thread
  rule), and `collectForm`'s stream-special probe therefore also marks the three stream
  specials special there.
- The restore loop is the cleanup form of an `unwind-protect`, so every exit the compilers
  cover for unwind-protect restores through the same emitter; the holes below are neither
  widened nor narrowed. On WASM this is why `progv` FORCES EH MODE (`usesEhForm` lists it).
- A name in NO arm (CL allows binding an undeclared symbol) is bound in the eval runtime's
  global env mirror (`_genv`/`GLOBAL_ENV`) via `%progv-genv`/`%progv-genv-set`, which expose the
  mirror as a Lisp alist (its nodes ARE cons cells on both backends) so the maintenance is plain
  Lisp in the expansion. Mirror maintenance is included only when the eval runtime exists
  (`Ctx.evalStoreRef != null` / `Ctx.usesEval` -- progv does NOT force it). Both flags are
  carried by EVERY compile context, not just the top-level one, so a progv inside a defun still
  maintains the mirror.
- `symbol-value` is DYNAMIC-FIRST in a progv-using program
  (`LispMacroExpander.dynamicFirstSymbolValue`, gated on `Ctx.usesProgv`): the runtime name
  dispatches over the special set and a match reads the VARIABLE, falling back to the raw mirror
  probe (`%symbol-value-raw`). This is what makes cl-json's
  `(progv vars (mapcar #'symbol-value vars) ...)` see values its decoder `setq`s in the
  enclosing extent -- the mirror alone cannot, because `setq` does not write it (limitation 2).
  Programs without progv keep the raw emission byte-identically. `#'symbol-value` has a
  REFERENCE-GATED `BuiltinFunctionWrappers` entry: the `(function symbol-value)` spelling that
  injects the wrapper is a symbol occurrence the `usesEval` scan counts, so the body is real.
- The literal-`boundp` fold refuses progv programs (`CompileTimeBoundp.fold` gate): the lowering
  can create a runtime-named mirror binding, so a literal probe is not decidable.
- Deliberate divergences from the interpreter, both narrow: a non-symbol in the symbols list is
  not detected (it matches no arm and binds a useless mirror entry instead of signalling), and a
  closure that CAPTURED a special reads its capture even under `symbol-value`.

## Compile-path limitations (interpreter unaffected)

1. Exit restores are covered for `return`/`return-from` compiled as a DIRECT branch inside the
   binding function (`Ctx.specialBindScopes`). Remaining holes: a WASM plain `return` that also
   crosses an `unwind-protect`/`handler-case` region goes through the trampoline cascade, which
   does not know the save slots; `go` across a special `let` does not restore; an ERROR caught
   by a `handler-case` outside the `let` skips the restore
   (`(handler-case (let ((*x* 2)) (error "boom")) (error (e) *x*))` answers 2 on the JVM and
   leaves 2 bound -- on a per-request virtual thread the leak dies with the thread); and a
   `return-from` crossing a LAMBDA boundary (the block-exit throw/catch lowering) skips the
   restore on the JVM and both wasm-GC backends, which corrupts cl-ppcre's scanner (stale
   `*reg-starts*` after a failing register-regex loop). Fixing the last needs a save STACK, not
   catch-site slot restores: the slots live in the thrower's dead frames.
2. `symbol-value`/`boundp`/`eval` see the global default, not a dynamic binding, on the compile
   path -- EXCEPT `symbol-value` in a progv-using program (above). They read the
   `_genv`/`GLOBAL_ENV` mirror, which the shallow save/restore does not update. Direct
   reads/`setq` of the special are correct, and both stores agree again after the `let`. Only
   bites a program that dynamically binds a special AND introspects it on a compiled backend.
   The mirror does now carry the global default for the three standard stream variables
   (`.kb/symbol-runtime-api.md`); that was a separate absence.
3. A lambda/defun parameter named like a special is still lexical (interpreter and compilers).

## Relationship to the two hand-rolled precedents

- `*package*` has TWO faces. Resolution-time: `PackageResolver` tracks the current package
  across the forms it walks (`in-package`, `pushPackage`/`popPackage` + the
  `%push-package`/`%pop-package` markers). Run-time: `*package*` is a genuine variable holding
  the package keyword, kept in step by resolving `in-package` (and the pop marker) to
  `(setq *package* :P)`. On the compile paths the run-time face is an ordinary special of this
  file (an injected `(defvar *package* :cl-user)` when the program reads it); on the
  interpreter the two faces are ONE cell, which is why it is the one special the interpreter
  does NOT thread-scope. `.kb/packages.md`.
- Macro-time setf replay is separate: cl-who reads `*html-mode*` at macro-EXPANSION time, so
  `UserMacroExpander` replays a top-level `(setf (PLACE) ...)` into its macro-time evaluator.
  The decision is a static purity judgment (`isPureConfigSetf`/`isPure`), deny-by-default, so no
  external effect double-runs. `.kb/asdf.md`.
- A macro-time global's VALUE is demand-driven; its SPECIAL proclamation is not.
  `LispEvaluator.registerLazyGlobal` adds the name to `specialVars` at once but parks the value
  expression as a thunk (`Environment.defineLazy`). Two couplings hold the replay together and
  both fail SILENTLY (wrong value, not a crash): `Environment.isBound` must count a pending
  thunk (`isGlobalOrSpecialVariable` is half the purity walk's "is this config state" test, and
  `defvar` idempotence rides on it), and `Environment.set` must DISCARD a pending thunk, or the
  replayed `(setf *html-mode* :html5)` is later overwritten by the original `defvar` default.

## Tests

`LispEvaluatorTest` (`specialVar*`/`progv*`/`defparameter`/`declaim`/`proclaim`/thread-scoped),
`JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest` (the `specialVar*` and `progv*`
groups -- nested binds, an undeclared name via `symbol-value`, extra symbols to nil, restores
after normal return / `return-from` / `go` / an error caught outside, the setq-visible
`symbol-value` snapshot; JVM adds `specialVarBindingIsThreadScoped` and
`specialVarSetqOutsideAnyBindingReachesTheGlobal`), `JvmThreadTest`
`spawnedThreadDoesNotInheritTheSpawnersDynamicBindings`,
`NoGcWasmCompilerTest.rejectsSpecialVariableDeclaration`,
`WasmReentrantE2eTest.overlappedCallsEachReadTheirOwnDynamicBinding`,
`WasmReentrantCompilerTest`, `ClJsonE2eTest` (progv consumer, all four backends), ci-spec
`special-variable-dynamic-binding` and `progv-compiles-on-every-backend`.
