# `eval` in all three backends (interpreter, WASM, JVM)

A runtime tree-walking interpreter sharing the compiled value representation (`null`=nil, `Long`=int, `Double`=float, `String`=symbol or `"..."`-prefixed string, `Object[2]`=cons, `Object[]` with an `Integer` head=function value; interpreted closures use the sentinel `funcId == -1`). Interpreter: a `LispFunction` registered in `LispEvaluator`'s constructor (avoids a circular dep). WASM (`WasmEvalRuntimeBuilder`/`WasmEvalCompiler`) and JVM (`JvmEvalRuntimeBuilder`/`JvmEvalCompiler`) mirror each other: five functions `_lookup`/`_env_lookup`/`_eval`/`_apply`/`_store` plus a persistent top-level env (`GLOBAL_ENV` wasm global / `_genv` JVM field). Emitted only when the program calls `eval` (`programUsesEval`); WASM keeps stubs to hold fixed function indices, JVM needs none (methods are by name). WASM trick: the `StringTable` dedups strings, so a quoted symbol and a `let`/`lambda`/`setq` name share one data offset and lookup is an `i32` offset compare; JVM uses `String.equals`/`instanceof` directly. `setq`/`setf`/`push`/`pop` all delegate to `_store`.

## `apply` no longer turns the interpreter on (WASM; todo-315)

`_apply` + the SPREAD dispatcher are a separate tier below the interpreter, gated by
`LispMacroExpander.needsApplyRuntime` (plus the `referencesApplyingWrapper` clause for the
injected `#'mapcar`-family/`#'funcall` wrapper bodies, which are added after the scan):

- `(apply #'name ...)` / `(apply 'name ...)` whose literal target names a top-level defun
  (or, for the `#'` spelling, an injectable built-in wrapper -- the `'name` spelling does
  not fire the wrapper-injection gate, so it counts only against defuns) compiles to a
  physical direct call (`Wasm/JvmApplyCompiler`) and forces NOTHING. `(print (apply #'+
  (list 1 2)))` costs 27 bytes over the base program where it used to cost ~13 KB
  unoptimized.
- A computed designator, a literal `lambda` designator, a `multiple-value-call`, an apply
  of a `flet`/`labels`-bound name (the call-site rewrite makes the designator a variable
  -- the scan tracks the local names), or an unknown literal target forces
  `usesApplyRuntime`: the real `_apply` body (WITHOUT the `$fenv` and interpreted-closure
  arms -- `buildApplyBody(usesEval)`; nothing can create either without `_eval`/`_store`),
  the spread dispatcher body, and the `_lookup` registry (the registry-live arg of
  `dispatchableFuncIds` and the registry-blob gate both include `usesApplyRuntime`, so a
  runtime SYMBOL designator still resolves).
- `eval`/`load`/`--dynamic`/`boundp`/`symbol-value`/`fboundp`/`fmakunbound`/
  `(setf (symbol-function ...))` still force the full eval runtime, which implies the
  apply tier.

**The JVM deliberately keeps `apply` (and multiple-value-call, and `#'funcall`) forcing
`usesEval`**: measured 2026-08-10, a trivial `(print (+ 1 2))` JVM class already carries
`_eval`/`_apply`/`_store`/`_lookup` and every `_invoke_N` -- the always-injected wrapper
bodies reference `_apply`, the post-compile self-check (`gateGroupFor`/`GateUnderpredicted`,
`.kb/adjustable-arrays.md`) forces `GROUP_EVAL` on, and the class shaker is what trims it
under `--optimize`. Narrowing the JVM gate would change nothing but add a guaranteed
re-compile pass. Re-evaluation trigger: if the JVM wrapper set ever becomes
reference-gated like the WASM spread tier, revisit.

## A literal `boundp` never reaches this gate

`boundp` is in the OR-chain above, but a `(boundp 'name)` over a LITERAL symbol is
decided at compile time and is gone before the scan runs: the program is closed, so the
answer is which top-level forms before it declare the name a global.
`compiler/CompileTimeBoundp` folds it on both compile paths (and, in the CLI and the
playground, before the tree-shaker, because the `(unless (boundp '+k+) (defconstant +k+
v))` guard is also what keeps a library constant from being a top-level definer). Only a
COMPUTED designator -- `(boundp (intern ...))` -- still opens the gate. Full mechanics,
what is deliberately not decidable, and why `fboundp` is left out:
`.kb/compile-time-boundp.md`.

**Top-level global mirroring**: when `usesEval`, a top-level `setq`/`defvar`/`defparameter`/`defconstant` in compiled code (the `Ctx.topLevel` context) also calls `_store(name, value, genv)` to copy the binding into the eval global env, so an eval'd expression can resolve a global the compiled program defined (`Jvm/WasmSetqCompiler.mirrorTopLevelGlobal`; the compiled value still lives in a `main`/`_start` local, the mirror is write-through one-way).

**Only a name with a global backing store is mirrored** -- `ctx.globals`/`ctx.globalIndices`, the dedicated store every `defvar` and every top-level `setq` place gets (`compiler/GlobalVarCollector`). A LEXICAL of a top-level form is not mirrored: CL's `eval` resolves against the null lexical environment, so no eval'd form can name a top-level `let`/`loop`/`do` variable -- and the macro expanders' temporaries (`__loop_acc0`, the `while` cursor, `__nrev_*`) are symbols in no package at all. The gate is `Jvm/WasmSetqCompiler.mirrorsTopLevelGlobal(name, ctx)`, and it is a NAME test, not a scope test, because `GlobalVarCollector` is deliberately scope-blind (a name that is only ever a `let` variable still gets a store it never uses): the plain-lexical arm of each `setq` emitter therefore does not call the mirror at all. `_store` is an `_envLookup` -- a linear walk of an alist with a `String.equals` per entry -- so mirroring a loop variable cost one walk per assignment per iteration: 7.1x on the JVM and 3.0x on wasm-GC for `(print (loop for i from 1 to 10^8 sum i))`. Pinned by `Jvm/WasmLispCompilerTest.aTopLevelLexicalIsNotMirroredIntoTheEvalGlobalEnv`.

Supported forms and the limitations are identical across WASM/JVM and listed in the README "Compiled `eval` limitations".
