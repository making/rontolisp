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
- **The body is a PROTECTED REGION of the unwind-protect machinery**
  (`JvmUnwindProtectCompiler.Region`, opened by `JvmLetCompiler`) whose cleanups are the
  internal `(%dyn-restore tlField saveSlot)` forms (`LispNames.DYN_RESTORE_INTERNAL`), innermost
  first. So the restore rides every exit channel that machinery covers: normal completion, the
  catch-any handler (an error caught in this frame or across a callee's, a cross-lambda
  `%nlx-throw`, `catch`/`throw`), and the `return`/`return-from`/`go` inlining of escaped scopes'
  cleanups -- interleaved with user cleanups in CL's innermost-first order, since a binding IS an
  unwind scope. One mechanism, no separate exit-restore list (the former `Ctx.specialBindScopes`
  covered the direct-branch exits only and leaked `*reg-starts*` on the rest). The restores are
  stack-neutral, so the value stays on the operand stack under them (no result slot). A
  body-less `let` has no region. A special `let` on the tail spine keeps joining it:
  `JvmBodyOutliner.readyToSplit` ignores a scope whose cleanups are all compiler-internal, because
  the region's exception range covers the continuation CALL and nothing in a continuation can
  leave the region lexically.
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
  `Ctx.specialVars`, dual-bind, dual `setq` (`WasmSetqCompiler`), and the same protected region
  (`WasmUnwindProtectCompiler.compileRegion` with `%dyn-restore` cleanups, one `UnwindScope`
  kind that exists outside EH mode too): in EH mode a `try_table` landing pad that restores and
  rethrows the payload on its tag, whose refresh keeps only the SAVE slots
  (`wasm-landing-pad-refresh.md`); in every mode the plain-`return` trampoline cascade, and the
  `return-from`/`go` inlining of escaped scopes. Outside EH mode with no enclosing
  plain-`return` boundary the emission is bare -- body then restores, byte-identical to before.
  Same for `--component`. `--no-gc` `NoGcWasmCompiler` rejects `defvar`/`declaim` at top level
  outright.

## progv on the compile paths

`progv` compiles on the JVM and both WASM backends. Bound symbols are runtime values but the
candidate SPECIALS are static -- that asymmetry is the design
(`LispMacroExpander.expandProgvForCompile`, shared by both compilers; the interpreter keeps
native `evalProgv`).

- Lowers to a loop over the runtime symbol list dispatching each name through an `equal`
  chain over the program's special set; a matching arm is `(%progv-dyn-bind NAME value)`
  (`Jvm/WasmProgvCompiler`, `WasmDynVars.emitProgvBind` under `--reentrant`). Previous binding
  state flows as a VALUE consed onto a save list -- bind and restore sit in different loop
  iterations, so a save slot cannot work. The chain's DEPTH is the backend's business:
  the wasm backend compiles else-chains iteratively (below), so hundreds of specials
  cost no compile stack.
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
- The name dispatch is a chain of one `if` per special, and so is the `progv`
  bind/unbind lowering above -- a 315-special program nested 315 `if`s. That is fine
  for the EMISSION (linear, small constants) but the wasm backend used to compile an
  else-chain with one Java frame per level, so it overflowed a 1 MiB compile stack
  cold on the ci-spec corpus (2026-09-25, `WasmTreeShakerCorpusTest » StackOverflow`
  on CI while every local box stayed green -- the same JIT-dependent margin
  `interpreter-stack.md` records for the interpreter). Else-chains now compile
  iteratively (`WasmIfCompiler.compile` descends the else spine in a loop and closes
  the deferred then-arms on the way back out), so depth costs no Java stack: the
  corpus compiles cold at `-Xss384k` on fresh JVMs, and the emission is byte-identical
  (the corpus compiles to the same bytes at all three optimize levels as before).
  Pinned by `WasmLispCompilerTest.aDeepElseChainCompilesOnAMegabyteStack` (a thousand
  specials through the real dispatch on a 1 MiB thread; fails with `StackOverflow`
  without the loop).
- The literal-`boundp` fold refuses progv programs (`CompileTimeBoundp.fold` gate).
- Deliberate divergences: a non-symbol in the symbols list is not detected, and a closure that
  CAPTURED a special reads its capture even under `symbol-value`.

## Compile-path limitations (interpreter unaffected)

1. Exit restores are covered on EVERY channel on all four backends since 2026-09-12 -- an error
   caught outside the `let` (same frame or across a callee's), `catch`/`throw`, `go`, a plain
   `return` through the wasm trampoline cascade in either nesting order against an
   `unwind-protect`, and a `return-from` crossing a lambda boundary (cl-ppcre's scanner shape:
   a failing register scan used to leak `*reg-starts*` into every later zero-register scan).
   Pinned by `specialLetRestoresOnEveryExit` on `LispEvaluatorTest` / `JvmLispCompilerTest` /
   `WasmLispCompilerIntegrationTest` (P1 + component), ci-spec
   `special-let-restores-on-every-exit`, and `ClPpcreE2eTest`'s failing-one-register scan
   between two zero-register scans. What is NOT an exit: a wasm-GC RAW trap (`(car 5)`, a failed
   cast) ends the module, restore moot. The mechanism is thrower-side (each binding frame
   restores its own on the way out), which is why no catch-site save stack was needed: the
   `.todo/192` sketch's objection -- the slots live in the thrower's dead frames -- holds only
   for a CATCHER doing the restore.
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

## Cost of the every-exit restore (2026-09-12)

Minimal programs, JVM `.class` / wasm Preview 1 bytes, before -> after (the `.class` is the
bigger mover because each region adds an exception-table entry, a handler and two stack-map
frames; wasm adds only the trampoline block, and in EH mode the pad):

| program | JVM | wasm |
|---|---|---|
| `(print (+ 1 2))` | 3,948 -> 3,948 | 489 -> 489 |
| `(defvar *x* 1) (print (let ((*x* 2)) *x*))` | 4,468 -> 4,522 | 11,175 -> 11,175 |
| the same `let` inside a `dolist` | 4,815 -> 4,847 | 11,383 -> 11,397 |
| the same `let` under a `handler-case` (EH mode) | 11,011 -> 11,074 | 13,732 -> 13,761 |
| `(unwind-protect (+ 1 2) (print *x*))`, no special `let` | 4,105 -> 4,105 | 12,375 -> 12,375 |
| cl-ppcre (`asdf:load-system` + three scans) | 725,206 -> 755,211 (+4.1%) | 693,247 -> 700,485 (+1.0%) |

A program that binds no special is byte-identical; a wasm program binding one outside EH mode and
outside any loop block is too. cl-ppcre is the outlier because `(declare (special ...))` is
honored program-wide, so hundreds of its `let`s bind specials and each pays ~70 B on the JVM.

## Tests

`LispEvaluatorTest` (`specialVar*`/`progv*`/`defparameter`/`declaim`/`proclaim`/thread-scoped,
`specialLetRestoresOnEveryExit`), `JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest`
(`specialVar*` and `progv*` groups, `specialLetRestoresOnEveryExit`; JVM adds
`specialVarBindingIsThreadScoped`, `specialVarSetqOutsideAnyBindingReachesTheGlobal`),
`JvmThreadTest` `spawnedThreadDoesNotInheritTheSpawnersDynamicBindings`,
`NoGcWasmCompilerTest.rejectsSpecialVariableDeclaration`,
`WasmReentrantE2eTest.overlappedCallsEachReadTheirOwnDynamicBinding`,
`WasmReentrantCompilerTest`, `ClJsonE2eTest`, `ClPpcreE2eTest`, ci-spec
`special-variable-dynamic-binding`, `progv-compiles-on-every-backend`,
`special-let-restores-on-every-exit`.
