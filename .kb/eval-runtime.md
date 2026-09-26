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
  `eval`/`load`/`--dynamic`/`boundp`/`symbol-value`/`set`/`fboundp`/`fmakunbound`/
  `(setf (symbol-value ...))`/`(setf (symbol-function ...))` force the full runtime.
- **JVM apply tier** (2026-09-19, `.todo/894`; before it, any `apply`/`multiple-value-call`
  forced the whole eval runtime): `usesApplyRuntime` = `usesEval` ||
  `needsApplyRuntime(program, applyGateWrappers)` (the WASM scan; a literal target naming a compiled
  function is a direct call in `JvmApplyCompiler`) || a reachable applying wrapper ||
  `GROUP_APPLY` forced. It emits `_apply` (`buildApply(ec, withEval=false)`: no `_fenv` arm, no
  interpreted-closure arm), the spread dispatcher `_invoke_v`, `_notFn`/`_arityChk` and `_lookup` --
  not `_eval`/`_store`/`_envLookup`, the `_genv`/`_fenv` fields, or a dispatcher per arity.
  `gateGroupFor("_apply")` is `GROUP_APPLY`, so a mispredicted apply site costs a re-run with the
  tier, never the interpreter. `(defun ap (f l) (apply f l))`: 48,251 -> 24,099 B of class.
- **Applying wrappers.** `BuiltinFunctionWrappers.APPLY_USING_FUNCTIONS` is injected exactly when
  the program can reach one (`referencesFunctionDesignator`, position-blind on purpose; a name
  read or built at run time), and that reference is what brings the tier in. **A computed
  funcall/apply target counts only beside a symbol constant spelling one of the names**
  (`BuiltinFunctionWrappers.spellsSymbolConstant`: quoted data at any depth, or `#'`): the registry
  answers only names the program loads as values. Counting every computed target put the eval
  runtime into every higher-order function -- `(defun app (f x) (funcall f x))`: 49,177 -> 9,719 B
  -- and into every program carrying `%stream-target`, whose synonym arm funcalls a closure.
  **Trap: inject those bodies unconditionally and the class calls an undeclared `_apply`**; the
  post-compile self-check (`gateGroupFor`/`GateUnderpredicted`, `.kb/adjustable-arrays.md`) then
  forces the group on eval-free programs -- 4 KB -> 34 KB once one top-level global made the
  mirror real, when the group was still `GROUP_EVAL`. `GateUnderpredicted` stays as a backstop;
  the ARRAY gate had the same problem. Pinned by `JvmLispCompilerTest.
  aComputedFuncallTargetCarriesNoApplyOrEvalRuntime`,
  `aComputedTargetBesideAQuotedWrapperNameStillReachesTheWrapper`,
  `aRuntimeApplyGetsTheApplyTierNotTheInterpreter`,
  `anApplyOfALiteralCompiledTargetNeedsNoApplyRuntime`.
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

## Argument counts

**Invariant: a wrong argument count inside a compiled `eval` is the interpreter's `program-error`,
with its text, on both backends** (2026-09-26). Pinned by ci-spec
`eval-wrong-arity-signals-program-error`, `JvmLispCompilerTest.
compileAndRunEvalReportsAWrongArgumentCountAsTheInterpreterDoes`, `WasmLispCompilerIntegrationTest.
evalReportsAWrongArgumentCountAsTheInterpreterDoes`.

- **A registered function gets EVERY argument form evaluated** and `_apply` hands the list to the
  spread dispatcher, whose case guard (`_arityChk` / `_arity_chk`) judges the count and names the
  operator ([error-handling.md](error-handling.md), "A wrong argument COUNT"). The registry's
  arity used to be the number of forms `_eval` evaluated, padding with nil and dropping the
  surplus: `(car 1 2)` raised a type-error on `1`, `(cons 1)` answered `(1)`.
- **`= < > <= >= /=` chain in `_eval`** (`comparisonChain` / `emitComparisonChain`): their wrappers
  stay binary, since a sort predicate is a two-argument call and a variadic wrapper would cons a
  rest list per comparison, so the arm evaluates every argument and tests adjacent pairs (every
  pair for `/=`) through the binary wrapper. Without it the first bullet would have turned the old
  "extra arguments ignored" (`(< 1 3 2)` => T) into a count error. `(<)` reports `< expects at
  least 1 argument` through `_arityChk` with the operator's shape; on wasm the shape carries the
  `<` wrapper's funcId when it is in the named set, and the call traps where the module reports
  no count (no EH landing pad).
- **`apply` is a catalog wrapper** (`BuiltinFunctionWrappers.applyWrapper`, `(f a &rest r)`), so
  `eval` reaches it through the registry like any name and the report says `APPLY expects at least
  2 arguments`; it also made `#'apply` compile. It refuses a last argument that is no proper list
  with the interpreter's `APPLY: last argument must be a list`. `apply` was unknown inside `eval`
  before, which is why `(eval '(apply #'car '(1 2)))` answered nil.
- **An interpreted closure checks its count only without a `&` marker** in its lambda list: the
  runtime `lambda` binds such a list positionally (documented), so its parameter count is no
  count a call must match. The check is `_arityChk(argList, 2 * params)` in `_apply`'s closure
  arm; on wasm it exists only where `_arity_chk` does.
- **What a fixed-arity wrapper now reports** instead of silently dropping: `find` / `find-if` with
  `:test`/`:key`, `sort` with `:key`, `make-list` with `:initial-element` (`FIND expects 2
  arguments, got 4`), the same answer `(funcall #'find ...)` gives compiled. Most sequence
  wrappers already take their keywords.
- **Size** (2026-09-26, `--class-name P` / wasm Preview 1 bytes): `(print (eval '(+ 1 2)))`
  317,887 -> 319,303 JVM, 247,064 -> 247,775 wasm; the same under `handler-case` 449,779 ->
  451,309 / 375,740 -> 376,831. A program without `eval` is unchanged.

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
