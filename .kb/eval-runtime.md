# `eval` in all three backends (interpreter, WASM, JVM)

Runtime tree-walking interpreter over the compiled value representation: `null`=nil, `Long`=int,
`Double`=float, `String`=symbol or `"..."`-prefixed string, `Object[2]`=cons, `Object[]` with
`Integer` head=function value; interpreted closures use sentinel `funcId == -1`. Supported forms and
limitations identical across WASM/JVM -- README "Compiled `eval` limitations".

## Shape
- Interpreter: a `LispFunction` registered in `LispEvaluator`'s constructor (avoids a circular dep).
- WASM (`WasmEvalRuntimeBuilder`/`WasmEvalCompiler`) and JVM (`JvmEvalRuntimeBuilder`/`JvmEvalCompiler`)
  mirror each other: `_lookup`/`_env_lookup`/`_eval`/`_apply`/`_store` + a persistent top-level env
  (`GLOBAL_ENV` wasm global / `_genv` JVM field). `setq`/`setf`/`push`/`pop` delegate to `_store`.
- Emitted only when `programUsesEval`. WASM keeps stubs to hold fixed function indices; JVM needs none.
- WASM `StringTable` dedups strings -> lookup is an `i32` offset compare; JVM uses
  `String.equals`/`instanceof`.

## WASM: `apply` is a tier below the interpreter
Gate `LispMacroExpander.needsApplyRuntime`, plus a `referencesApplyingWrapper` clause for the injected
`#'map*`/`every`/`some`/`#'funcall` wrapper bodies (added after the scan).
- `(apply #'name ...)`/`(apply 'name ...)` on a literal top-level defun -- or, for `#'` only, an
  injectable built-in wrapper (`'name` does not fire the wrapper-injection gate) -- becomes a direct
  call (`Wasm/JvmApplyCompiler`), forcing nothing.
- Computed designator, literal `lambda` designator, `multiple-value-call`, apply of a `flet`/`labels`
  name (call-site rewrite makes the designator a variable; the scan tracks local names), or unknown
  literal target -> `usesApplyRuntime`: the `_apply` body without the `$fenv`/interpreted-closure arms
  (`buildApplyBody(usesEval)`), the spread dispatcher, and the `_lookup` registry
  (`dispatchableFuncIds`' registry-live arg and the registry-blob gate include `usesApplyRuntime`, so
  a runtime SYMBOL designator still resolves).
- `eval`/`load`/`--dynamic`/`boundp`/`symbol-value`/`fboundp`/`fmakunbound`/
  `(setf (symbol-function ...))` force the full eval runtime, implying the apply tier.

## JVM: `apply`/`multiple-value-call` force `usesEval`
No separate apply tier. `BuiltinFunctionWrappers.APPLY_USING_FUNCTIONS` is injected exactly when the
program can reach one -- `#'name`/`'name` anywhere (`referencesFunctionDesignator`, position-blind on
purpose), a computed `funcall`/`apply` target, or a name read/built at run time -- and that reference
is also what forces `usesEval`.
- Trap: inject those bodies unconditionally and the class calls an undeclared `_apply`; the
  post-compile self-check (`gateGroupFor`/`GateUnderpredicted`, `.kb/adjustable-arrays.md`) then forces
  `GROUP_EVAL` on eval-free programs -- 4 KB -> 34 KB once one top-level global makes the mirror below
  real. `GateUnderpredicted` stays as backstop for genuinely under-predicted gates; the ARRAY gate had
  the same wrapper problem (`FILL`/`COERCE`/`VECTOR`/`SVREF`/... call `_aset1`).
- Pins: `JvmLispCompilerTest.aProgramThatNeverMentionsEvalCarriesNoEvalRuntime`,
  `namingOneOfTheApplyingWrappersBringsTheEvalRuntimeBack`.

## Name-registry gate
JVM: `_lookup` when `usesEval || usesRuntimeFunctionDesignator || !indirectCallArities.isEmpty()`. The
source scan reads only `funcall`/`apply`; the last clause covers every other designator-caller
(`mapcar`, `sort`, `remove-if`, `maphash`, `(f x)` with an expression head) and is effectively always
true, since injected wrapper bodies take the designator as a PARAMETER and so dispatch in every program
(measured: `(print (+ 1 2))` dispatches `__every_pred`, `__reduce_gfn` and a dozen more). Narrowing it wants
demand-driven wrapper injection or the WASM scheme below (not built).

WASM cannot afford an always-true clause (wit-import module bytes are pinned); both halves are recorded
during EMISSION, not scanned off the source:
- **Symbol can ARRIVE**: `Ctx.runtimeDesignatorDispatch`, set wherever Pass 2 dispatches a designator
  that is not `#'name`/`'name`/a literal `lambda` (`LispMacroExpander.isStaticFunctionDesignator`) --
  `WasmDesignatorCall.prepare` (map family, `reduce`, `sort`), `WasmFunctionCallCompiler.compileFuncall`,
  `WasmHashTableCompiler.compileMaphash`. Must be emission-time: `(every f l)` becomes a Pass 2 `do`
  over `(funcall #pred elem)`, so a source scan misses `every`, `remove-if`, `count-if`, `find-if`,
  `position-if`.
- **Name can ANSWER**: `Ctx.userSpelledLiterals` (user-spelled half of `spelledLiterals`) holds a
  defun-name spelling (`DesignatorSpellings`), or the program can build/read one (`nameResolvable`).
- Both halves exclude the INJECTED runtime, whose bodies funcall a designator parameter and quote
  `'list`/`'cons`/`'string` for their own `coerce`; unfiltered, the gate is true for `(print (+ 1 2))`.
  `Ctx.injectedRuntimeBody` marks those bodies while emitted, `Ctx.injectedRuntimeLambdas` carries the
  mark to lambdas they build (`stable-sort`'s comparator, `complement`'s closure) so Pass 2c re-enters
  them with the same answer.
- Pins: `WasmLispCompilerTest.onlyAnUnreadableDesignatorPullsInTheNameRegistry`,
  `WasmLispCompilerIntegrationTest.aComputedSymbolDesignatorResolvesForEveryOperatorThatCallsIt`
  (+ `--component` twin).

## A literal `boundp` never reaches the gate
`compiler/CompileTimeBoundp` folds `(boundp 'name)` over a literal symbol on both compile paths, and in
the CLI/playground before the tree-shaker (the `(unless (boundp '+k+) (defconstant +k+ v))` guard also
keeps a library constant from being a top-level definer). Only a computed designator
`(boundp (intern ...))` opens the gate. `.kb/compile-time-boundp.md` (incl. why `fboundp` is out).

## Top-level global mirroring
When `usesEval`, a top-level `setq`/`defvar`/`defparameter`/`defconstant` (`Ctx.topLevel`) also calls
`_store(name, value, genv)` so an eval'd form resolves it
(`Jvm/WasmSetqCompiler.mirrorTopLevelGlobal`); the compiled value stays in a `main`/`_start` local, so
the mirror is write-through one-way.

**Only a name with a global backing store is mirrored** -- `ctx.globals`/`ctx.globalIndices`, the store
every `defvar` and top-level `setq` place gets (`compiler/GlobalVarCollector`). A top-level LEXICAL is
not: CL's `eval` resolves against the null lexical environment, and expander temporaries
(`__loop_acc0`, the `while` cursor, `__nrev_*`) are symbols in no package. `mirrorsTopLevelGlobal(name,
ctx)` is a NAME test, not a scope test, because `GlobalVarCollector` is deliberately scope-blind; the
plain-lexical arm of each `setq` emitter does not call the mirror at all. `_store` is an `_envLookup`
(linear alist walk, `String.equals` per entry), so mirroring a loop variable costs one walk per
assignment per iteration (7.1x JVM / 3.0x wasm-GC on `loop ... sum` to 10^8). Pin:
`Jvm/WasmLispCompilerTest.aTopLevelLexicalIsNotMirroredIntoTheEvalGlobalEnv`.
