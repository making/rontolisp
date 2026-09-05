# `eval` in all three backends (interpreter, WASM, JVM)

Runtime tree-walking interpreter over the compiled value representation: `null`=nil, `Long`=int,
`Double`=float, `String`=symbol or `"..."`-prefixed string, `Object[2]`=cons, `Object[]` with
`Integer` head=function value; interpreted closures use sentinel `funcId == -1`. Supported forms and
limitations are identical across WASM/JVM -- README "Compiled `eval` limitations".

## Shape

Interpreter: a `LispFunction` registered in `LispEvaluator`'s constructor (avoids a circular dep).
WASM (`WasmEvalRuntimeBuilder`/`WasmEvalCompiler`) and JVM (`JvmEvalRuntimeBuilder`/
`JvmEvalCompiler`) mirror each other: `_lookup`/`_env_lookup`/`_eval`/`_apply`/`_store` + a
persistent top-level env (`GLOBAL_ENV` wasm global / `_genv` JVM field), with
`setq`/`setf`/`push`/`pop` delegating to `_store`. Emitted only when `programUsesEval`; WASM keeps
stubs to hold fixed function indices, JVM needs none.

## Gating

- **WASM has an `apply` tier below the full runtime** (`LispMacroExpander.needsApplyRuntime` plus a
  `referencesApplyingWrapper` clause for injected `#'map*`/`every`/`some`/`#'funcall` bodies). A
  literal top-level target compiles to a direct call (`Wasm/JvmApplyCompiler`); a computed
  designator, literal `lambda`, `multiple-value-call`, an apply of a `flet`/`labels` name or an
  unknown literal target sets `usesApplyRuntime` (`buildApplyBody(usesEval)` without the
  `$fenv`/closure arms, the spread dispatcher, the `_lookup` registry).
  `eval`/`load`/`--dynamic`/`boundp`/`symbol-value`/`fboundp`/`fmakunbound`/
  `(setf (symbol-function ...))` force the full runtime.
- **JVM has no apply tier**: `apply`/`multiple-value-call` force `usesEval`.
  `BuiltinFunctionWrappers.APPLY_USING_FUNCTIONS` is injected exactly when the program can reach one
  (`referencesFunctionDesignator`, position-blind on purpose), and that reference is what forces the
  gate. **Trap: inject those bodies unconditionally and the class calls an undeclared `_apply`**; the
  post-compile self-check (`gateGroupFor`/`GateUnderpredicted`, `.kb/adjustable-arrays.md`) then
  forces `GROUP_EVAL` on eval-free programs -- 4 KB -> 34 KB once one top-level global makes the
  mirror real. `GateUnderpredicted` stays as a backstop; the ARRAY gate had the same problem.
- **Name-registry gate.** JVM: `_lookup` when
  `usesEval || usesRuntimeFunctionDesignator || !indirectCallArities.isEmpty()` -- effectively always
  true, since injected wrapper bodies take the designator as a PARAMETER (measured:
  `(print (+ 1 2))` dispatches `__every_pred`, `__reduce_gfn` and a dozen more). Narrowing it wants
  demand-driven wrapper injection or the WASM scheme. WASM cannot afford an always-true clause
  (wit-import module bytes are pinned), so **both halves are recorded during EMISSION, not scanned
  off the source**: `Ctx.runtimeDesignatorDispatch` (set at `WasmDesignatorCall.prepare`,
  `WasmFunctionCallCompiler.compileFuncall`, `WasmHashTableCompiler.compileMaphash` -- must be
  emission-time, since `(every f l)` becomes a Pass 2 `do` over `(funcall #pred elem)` that a source
  scan misses) and `Ctx.userSpelledLiterals` + `nameResolvable` for whether a name can ANSWER. Both
  halves exclude the INJECTED runtime (`Ctx.injectedRuntimeBody`, `Ctx.injectedRuntimeLambdas`
  carrying the mark into lambdas those bodies build); unfiltered, the gate is true for
  `(print (+ 1 2))`.
- **A literal `boundp` never reaches the gate**: `compiler/CompileTimeBoundp` folds `(boundp 'name)`
  on both compile paths and in the CLI/playground before the tree-shaker; only `(boundp (intern ...))`
  opens it (`.kb/compile-time-boundp.md`).

## Top-level global mirroring

When `usesEval`, a top-level `setq`/`defvar`/`defparameter`/`defconstant` (`Ctx.topLevel`) also calls
`_store(name, value, genv)` (`Jvm/WasmSetqCompiler.mirrorTopLevelGlobal`); the compiled value stays in
a `main`/`_start` local, so the mirror is write-through one-way.

**Only a name with a global backing store is mirrored** -- `ctx.globals`/`ctx.globalIndices`
(`compiler/GlobalVarCollector`). A top-level LEXICAL is not: CL's `eval` resolves against the null
lexical environment, and expander temporaries (`__loop_acc0`, the `while` cursor, `__nrev_*`) are
symbols in no package. `mirrorsTopLevelGlobal(name, ctx)` is a NAME test, not a scope test, because
`GlobalVarCollector` is deliberately scope-blind. `_store` is an `_envLookup` (linear alist walk), so
mirroring a loop variable costs one walk per assignment per iteration (7.1x JVM / 3.0x wasm-GC on
`loop ... sum` to 10^8).

## Tests

- `JvmLispCompilerTest.aProgramThatNeverMentionsEvalCarriesNoEvalRuntime`,
  `namingOneOfTheApplyingWrappersBringsTheEvalRuntimeBack`
- `WasmLispCompilerTest.onlyAnUnreadableDesignatorPullsInTheNameRegistry`,
  `WasmLispCompilerIntegrationTest.aComputedSymbolDesignatorResolvesForEveryOperatorThatCallsIt`
  (+ `--component` twin)
- `Jvm/WasmLispCompilerTest.aTopLevelLexicalIsNotMirroredIntoTheEvalGlobalEnv`
