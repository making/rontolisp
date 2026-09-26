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
`eval-wrong-arity-signals-program-error` and `eval-inline-operators-check-their-argument-count`,
`JvmLispCompilerTest.compileAndRunEvalReportsAWrongArgumentCountAsTheInterpreterDoes` /
`compileAndRunEvalChecksTheCountOfTheOperatorsItUsedToInline`, their `WasmLispCompilerIntegrationTest`
twins, `LispEvaluatorTest.theListAccessorsFuncallAndReduceReportAWrongArgumentCount`.

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
- **Every catalog wrapper takes its call position's keywords** (2026-09-26). `find` / `find-if` /
  `find-if-not`, `sort` and `make-list` were fixed-arity (`FIND expects 2 arguments, got 4` through
  `funcall` and inside `eval`); pinned by ci-spec `builtin-function-values-take-their-keywords`.
  - `find` family: a keyword-less call is the plain scan; a keyword call hands the keywords to the
    `position` wrapper of its kind and answers `(elt seq i)` -- one copy of the general keyword
    scan instead of three (inlined, each cost a JVM program naming `#'find` 12 KB).
  - `sort`: `(sort seq (if kw (lambda (x y) (funcall pred (funcall k x) (funcall k y))) pred))` --
    one sort site, and the shared merge sort is stable ([sort.md](sort.md)), so it answers the
    permutation of the call position's `stable-sort` decoration without inlining that expansion
    (+19 KB). The interpreter's `#'sort` routes a keyword call to its `stable-sort`.
  - `make-list`: `(make-list n :initial-element (getf kw :initial-element))`.
  - A trap found on the way: `expandSortWithKey` expanded `stable-sort` with arrays assumed, so the
    wrapper's keyword arm opened the JVM array runtime in EVERY program (`(print (+ 1 2))` 6,024 ->
    9,736 bytes); it takes the backend's array gate now.
  - Size (same method as below): `(print (eval '(+ 1 2)))` 323,818 -> 329,076 JVM, 250,901 ->
    255,334 wasm; `(print (funcall #'sort (list 3 1 2) #'<))` 32,718 -> 34,220 / 23,148 -> 23,433;
    `(print (funcall #'find 2 '(1 2 3)))` 14,567 -> 27,680 / 3,267 -> 3,271 (the `position`
    wrapper now travels with it); the `apply` properness check ([error-handling.md](error-handling.md))
    adds 565 / 454 to a `&rest`-forwarding `(apply #'list x r)` under `handler-case`.
    `(print (+ 1 2))` unchanged (6,024 / 348).
- **Size** (2026-09-26, `--class-name P` / wasm Preview 1 bytes): `(print (eval '(+ 1 2)))`
  317,887 -> 319,303 JVM, 247,064 -> 247,775 wasm; the same under `handler-case` 449,779 ->
  451,309 / 375,740 -> 376,831. A program without `eval` is unchanged.
- **The inline operators** (2026-09-26). An `_eval` arm handles only the call shape it is written
  for and hands any other to the generic application, whose registered wrapper reports the count
  naming the operator: `funcall` with no argument, `+ - * /` with none (`(+)` is 0 through the
  wrapper, `(-)` reports). `mapcar`/`mapc`, `reduce`, `first` ... `tenth`, `rest` and `nth` have
  NO arm any more: the arms walked one list only (`(mapcar #'+ '(1 2) '(10 20))` answered
  `(1 2)`), took a `:from-end` for the initial value, dropped a surplus argument, and threw a
  NullPointerException on `(first nil)` / `(reduce #'+ nil)` (a trap on wasm). What that needed:
  - a `reduce` catalog wrapper (`BuiltinFunctionWrappers.reduceWrapper`, `(f seq &rest kw)` fed
    back into `expandReduce`), which also made `#'reduce` compile at all; the interpreter's own
    `REDUCE` function value evaluates a keyword call through the same expansion;
  - on the JVM, the `funcall` wrapper kept under `usesEval` (`wrapperExcludes` dropped it unless
    the program spelled `#'funcall`, so `(eval '(funcall))` answered nil);
  - `eval` counts itself -- `_arityChk` / `_arity_chk` with an operator no callee carries
    ([error-handling.md](error-handling.md), "Inside a compiled `eval`").
  The interpreter's `first` ... `tenth`, `rest` and `nth` expand only a call of their own shape
  (`LispEvaluator`, `properLength`), so any other count reaches the Java built-in and reports
  (`FIRST expects 1 argument, got 2`); the expansion used to drop the surplus, and `(nth 1)`
  indexed past the form. `(funcall)` is a `program-error` there too.
- **Every parameter count answers** (2026-09-26). The JVM `_lookup` filtered out functions of more
  than `MAX_CALLABLE_ARITY` (7) parameters -- a leftover of the per-arity `_apply`; `_apply` hands
  every list to the spread dispatcher now -- so `(eval '(f a1 ... a8))` answered nil and
  `(funcall 'f ...)` was an undefined function. On wasm a defun past 10 parameters is a rest-list
  defun that counts itself ([wasm-callable-arity.md](wasm-callable-arity.md)).
- **Size of the above** (2026-09-26, same method): `(print (eval '(+ 1 2)))` 319,302 -> 323,589 JVM,
  247,775 -> 250,901 wasm; under `handler-case` 428,184 -> 432,840 / 359,713 -> 364,156 -- the
  reduce wrapper's keyword expansion is most of it. `(print (+ 1 2))` unchanged (6,023 / 348); an
  11-parameter defun called directly, wasm 1,426 -> 2,284; `(print (mapcar #'- '(1 2)))` 23,931 ->
  24,118 JVM, 9,673 -> 2,880 wasm.

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
