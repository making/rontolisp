# Dynamic (special) variable binding

A variable proclaimed *special* is bound with dynamic extent by `let`/`let*`/`progv`, by SHALLOW
BINDING -- the value lives in the ordinary global cell and a binding is save/set/restore over it.
On the interpreter and the JVM that cell is THREAD-scoped; WASM is not. Docs:
`doc/en/reference/special-forms/{progv,let,defvar,defparameter}.md`.

## What proclaims a name special

`SpecialVarCollector` (`am.ik.rontolisp`, the shared AST layer, so the interpreter -- which must
not depend on `compiler` -- can use it): `defvar`/`defparameter`/`defconstant`,
`(declaim (special ...))`, `(proclaim '(special ...))`. `LispNames.SPECIAL` is NOT registered as
a cl symbol (registering it would perturb pinned introspection counts). Earmuffs are style.

- Local `(declare (special x))` IS honored PESSIMISTICALLY: `collectForm` recurses into every
  form (skipping `quote`) and a name declared special anywhere is special program-wide
  (cl-ppcre's convert phase).
- `declare`/`special` heads are matched package-insensitively (`splitQualified`) -- under
  `(in-package p)` the resolver spells them `p::declare`/`p::special`.
- The interpreter collects at the top-level `eval(expr)` entry BEFORE evaluating.
- Symbol reads consult the dynamic store BEFORE the lexical chain, so a lambda or macro parameter
  whose name is special must ALSO bind dynamically (`LispEvaluator.apply` / `expandUserMacro`
  push/pop `DynamicBindings`).
- A special is always ALSO a global; on the compile path specials are unioned into the
  `GlobalVarCollector` set.

## Interpreter (`LispEvaluator`) -- full fidelity, thread-scoped

- `DynamicBindings` (`eval`): per-evaluator `ThreadLocal<Map<String, Deque<LispVal>>>`;
  `specialVars` = `ConcurrentHashMap.newKeySet()`.
- `evalLet` is two-phase when `specialVars` is non-empty (all inits in the outer env, then push
  specials / bind lexicals), `finally` pops; `let*` reuses it via `expandLetStar`. A fast
  lexical-only path runs when `specialVars` is empty.
- `evalSymbolRef`, `setq`, `symbol-value`, `boundp` consult `DynamicBindings` first, gated on
  `!specialVars.isEmpty() || progvUsed`. `evalProgv` sets `progvUsed`; extra symbols -> nil;
  progv-bound names need not be declared special. Restore fires on EVERY exit.

## JVM (`JvmLetCompiler`) -- thread-scoped, hybrid representation

- A special NEVER dynamically bound keeps the bare `_g$*` static field (one `getstatic`).
- A special that IS bound -- decided by `SpecialVarCollector.collectDynamicallyBound`, which
  walks the fully expanded program and single-step-expands built-in binding macros via
  `LispMacroExpander.expandBuiltinMacro` -- also gets a `private static ThreadLocal _d$*`,
  created in `<clinit>`, **NEVER lazily** (a racy first bind would mint two ThreadLocals),
  holding a one-element `Object[]` CELL. **A cell, not the value**: `nil` is Java `null`, so the
  value cannot mark "no binding on this thread". Over-collection costs a read;
  under-collection throws in `JvmLetCompiler` at compile time.
- Helpers (`JvmDynVarRuntimeBuilder`): `_dget`, `_dbind`, `_dset` (answers 0 when no binding, so
  the call site falls through to `putstatic _g$*`). `Ctx.dynVars` carries the fields.
- A special binding in `let` is a DUAL-BIND: `_dbind` the old cell into a temp AND store the
  value into a lexical slot (boxed when captured). The lexical slot exists ONLY so a closure
  built in the body can read the entry value after the extent ended (cl-ppcre's `end-string`).
- Read rule is DYNAMIC-FIRST (`JvmExprCompiler.compileSpecialRead`) so a callee's rebinding is
  visible; inside a closure the CAPTURE wins. `setq` of a dual-bound name writes BOTH
  (`JvmSetqCompiler.emitGlobalStore`); with no active binding it lands in `_g$*`.
- Each binding is pushed on `Ctx.specialBindScopes` (`{tlField, saveSlot, blockDepth}`) so a
  `return`/`return-from` out of an enclosing block also restores (`JvmReturnCompiler.emitExit`)
  -- without it cl-ppcre's scan closure leaked `*reg-starts*`.
- Non-special globals stay lexical under `let`
  (`JvmLispCompilerTest.lexicalGlobalLetStaysLexical`).
- A spawned thread does NOT inherit the spawner's bindings; `rontolisp:make-thread`'s bindings
  alist is the hand-over, and a program using the thread primitives forces EVERY special into
  the dynamically-bound set (`.kb/threads.md`).

## WASM (`WasmLetCompiler`) -- shallow binding over the module global

Deliberately NOT thread-scoped: a served component's concurrent tasks interleave on ONE
instance's single stack without preempting inside a synchronous handler body. Not a claim that
the wasm backends are concurrency-safe in general.

- JSPI already broke that: a `--no-wasi` reactor suspending through `WebAssembly.Suspending`
  (`.kb/wasm-import.md`) may re-enter the export before the first call resumes. The answer is
  the RE-ENTRY GUARD -- every export wrapper of a module that can suspend traps a second entry.
- `--reentrant` relaxes the guard and brings a PER-TASK store (`codegen.wasm/WasmDynVars`): only
  the specials `collectDynamicallyBound` names get a slot in a per-call TASK RECORD (a
  `TYPE_HASH_BUCKETS` array of nullable `TYPE_CELL`s in a module global), created by every
  export wrapper on entry, seeded by `_start`, and saved/restored by the import wrapper around
  the suspending host call. The JVM hybrid's rules carry over exactly. Every non-reentrant
  module is byte-identical.
- Base shape otherwise mirrors the JVM's over module globals (`(mut (ref null eq))`):
  `Ctx.specialVars`, dual-bind, dual `setq` (`WasmSetqCompiler`), `Ctx.specialBindScopes` exit
  restores (`WasmReturnCompiler` direct-br path, `WasmReturnFromCompiler`); same for
  `--component`. `--no-gc` `NoGcWasmCompiler` rejects `defvar`/`declaim` at top level outright.

## progv on the compile paths

`progv` compiles on the JVM and both WASM backends. Bound symbols are runtime values but the
candidate SPECIALS are static -- that asymmetry is the design
(`LispMacroExpander.expandProgvForCompile`, shared by both compilers; the interpreter keeps
native `evalProgv`).

- Lowers to a loop over the runtime symbol list dispatching each name through an `equal` chain
  over the program's special set; a matching arm is `(%progv-dyn-bind NAME value)`
  (`Jvm/WasmProgvCompiler`, `WasmDynVars.emitProgvBind` under `--reentrant`). Previous binding
  state flows as a VALUE consed onto a save list -- bind and restore sit in different loop
  iterations, so a save slot cannot work.
- `collectDynamicallyBound` returns EVERY special of a progv-using program. The restore loop is
  the cleanup form of an `unwind-protect`, so `progv` FORCES EH MODE on WASM (`usesEhForm`).
- A name in NO arm is bound in the eval runtime's global env mirror (`_genv`/`GLOBAL_ENV`) via
  `%progv-genv`/`%progv-genv-set`. Mirror maintenance is included only when the eval runtime
  exists (`Ctx.evalStoreRef != null` / `Ctx.usesEval` -- progv does NOT force it); both flags are
  carried by EVERY compile context, not just the top-level one.
- `symbol-value` is DYNAMIC-FIRST in a progv-using program
  (`LispMacroExpander.dynamicFirstSymbolValue`, gated on `Ctx.usesProgv`), falling back to
  `%symbol-value-raw`. This is what makes cl-json's `(progv vars (mapcar #'symbol-value vars)
  ...)` see values its decoder `setq`s in the enclosing extent. Programs without progv keep the
  raw emission byte-identically. `#'symbol-value` has a REFERENCE-GATED
  `BuiltinFunctionWrappers` entry.
- The literal-`boundp` fold refuses progv programs (`CompileTimeBoundp.fold` gate).
- Deliberate divergences: a non-symbol in the symbols list is not detected, and a closure that
  CAPTURED a special reads its capture even under `symbol-value`.

## Compile-path limitations (interpreter unaffected)

1. Exit restores are covered for `return`/`return-from` compiled as a DIRECT branch inside the
   binding function. Remaining holes: a WASM plain `return` also crossing an
   `unwind-protect`/`handler-case` region (the trampoline cascade does not know the save slots);
   `go` across a special `let`; an ERROR caught by a `handler-case` outside the `let`
   (`(handler-case (let ((*x* 2)) (error "boom")) (error (e) *x*))` answers 2 on the JVM and
   leaves 2 bound); and a `return-from` crossing a LAMBDA boundary, on the JVM and both wasm-GC
   backends, which corrupts cl-ppcre's scanner. Fixing the last needs a save STACK, not
   catch-site slot restores: the slots live in the thrower's dead frames.
2. `symbol-value`/`boundp`/`eval` see the global default, not a dynamic binding, on the compile
   path -- EXCEPT `symbol-value` in a progv-using program. They read the `_genv`/`GLOBAL_ENV`
   mirror, which the shallow save/restore does not update. Direct reads/`setq` are correct and
   both stores agree again after the `let`. The mirror does carry the global default for the
   three standard stream variables (`.kb/symbol-runtime-api.md`).
3. A lambda/defun parameter named like a special is still lexical, everywhere.

## The two hand-rolled precedents

- `*package*` has TWO faces: resolution-time (`PackageResolver`, `in-package`,
  `%push-package`/`%pop-package` markers) and run-time (a genuine variable kept in step by
  resolving `in-package` to `(setq *package* :P)`). On the compile paths the run-time face is an
  ordinary special of this file; on the interpreter the two faces are ONE cell, which is why it
  is the one special the interpreter does NOT thread-scope. `.kb/packages.md`.
- Macro-time setf replay is separate: `UserMacroExpander` replays a top-level
  `(setf (PLACE) ...)` into its macro-time evaluator under a deny-by-default purity judgment
  (`isPureConfigSetf`/`isPure`). `.kb/asdf.md`.
- A macro-time global's VALUE is demand-driven; its SPECIAL proclamation is not.
  `LispEvaluator.registerLazyGlobal` adds the name to `specialVars` at once but parks the value
  as a thunk (`Environment.defineLazy`). Two couplings fail SILENTLY (wrong value, not a crash):
  `Environment.isBound` must count a pending thunk, and `Environment.set` must DISCARD one, or
  a replayed `(setf *html-mode* :html5)` is later overwritten by the original `defvar` default.

## Tests

`LispEvaluatorTest` (`specialVar*`/`progv*`/`defparameter`/`declaim`/`proclaim`/thread-scoped),
`JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest` (`specialVar*` and `progv*` groups;
JVM adds `specialVarBindingIsThreadScoped`,
`specialVarSetqOutsideAnyBindingReachesTheGlobal`), `JvmThreadTest`
`spawnedThreadDoesNotInheritTheSpawnersDynamicBindings`,
`NoGcWasmCompilerTest.rejectsSpecialVariableDeclaration`,
`WasmReentrantE2eTest.overlappedCallsEachReadTheirOwnDynamicBinding`,
`WasmReentrantCompilerTest`, `ClJsonE2eTest`, ci-spec `special-variable-dynamic-binding`,
`progv-compiles-on-every-backend`.
