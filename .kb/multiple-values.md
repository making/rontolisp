# Multiple values -- syntactic tier (Phase 3 unit 3)

Origin: `.todo/57-multiple-values.md` (core subset of the
`.todo/32-multiple-value-system.md` wishlist; shipped 2026-07-05). Goal: the
`multiple-value-bind`-over-`floor`/`gethash` idioms that real CL library code
uses, WITHOUT paying for a runtime multiple-value representation.

## What ships

- `values` (classified as a CL **function**: `CL_FUNCTIONS`, an `Environment`
  function, a variadic `&rest` `BuiltinFunctionWrappers` entry -- the first
  variadic wrapper -- and an `expandValues` call-position expansion; NOT in
  `expandBuiltinMacro`, matching its "function-like operators are deliberately
  absent" rule, so `macroexpand-1` leaves `(values ...)` alone as in CL).
- `multiple-value-bind`, `multiple-value-list`, `multiple-value-call`,
  `nth-value` (CL_MACROS + `expandBuiltinMacro`; `multiple-value-call` as a
  macro deviates from CL's special operator, precedent: `error`).
- Secondary values for `floor`/`ceiling`/`round`/`truncate` (quotient +
  remainder) and `gethash` (value + present-p) -- ONLY inside the consumers.
- Two-argument `(floor a b)` etc. in ordinary contexts:
  `expandFloorFamilyDivisor` -> `(floor (/ a b))` (exact rational division,
  so integer quotients are exact; scalar `--no-gc` uses its float `/`, same
  rounding result). Wired as an arity-3-only branch in the evaluator (`break`
  falls through to the 1-arg built-in) and all three compilers.

## The lowering (LispMacroExpander)

`lowerMvProducer(form, prefix)` -> `MvProducer{bindings, values}`: ordered
temp bindings (`__mv<id>...`, `MV_COUNTER`-fresh like `FLET_COUNTER`) realized
as **nested single-binding lets** (`nestMvBindings`) so evaluation order is
guaranteed on every backend, plus pure value expressions over the temps.
Recognized producers (`isMvProducerForm`):

- literal `(values e...)` -> temps for each argument;
- `(floor|ceiling|round|truncate a [b])` -> `q = (op a)` / `(op (/ a b))`,
  remainder `(- a q)` / `(- a (* q b))`;
- `(gethash k h [d])` -> a runtime `(gensym)` sentinel is passed as the
  gethash default; `(eq v sentinel)` decides `(values stored-or-default
  present-p)`. Distinguishes a stored nil from a missing key on all backends
  (WASM `eq` on an i31/string vs the fresh symbol is safe -- pinned in
  `WasmLispCompilerIntegrationTest.multipleValueGethash`).
- anything else -> single temp bound to the form (single-value producer).

Consumers: `expandMultipleValueBind` (parallel inner `let` of the user vars
over the value expressions; missing -> nil, surplus values evaluated in the
bindings and dropped), `expandMultipleValueList` (`(list e...)` shortcut for a
literal `values`, `(list form)` for unknown producers), `expandNthValue`
(`(nth n <mv-list>)`; `nth` evaluates n first, CL order),
`expandMultipleValueCall` (fn temp bound FIRST, then each producer's temps ->
one direct `(funcall fnTmp v...)`; the value count is static, so no runtime
spreading and no `apply`).

`expandValues` (ordinary context): 0 args -> nil, 1 -> the form, n ->
`(prog1 ...)`.

## Semantics consequences (documented deviations)

- A `(values ...)` tail in a USER function collapses to the primary value at
  the call boundary (values->prog1 inside the callee); the caller's mv-bind
  reads extra vars as nil. Same on all four backends. This is the check-point
  for `.todo/61-split-sequence-e2e.md`: split-sequence's second value needs
  the runtime tier (stays in `.todo/32`).
- Producers are recognized before user-macro expansion on the interpreter but
  after it on the compile path (UserMacroExpander runs first), so a USER MACRO
  expanding to `(values ...)` yields all values only when compiled. Literal
  producers behave identically everywhere.
- `multiple-value-call` with a builtin `#'name` inherits the fixed wrapper
  arity (pre-existing: wrappers are unary/binary; a mismatched funcall
  silently yields nil on JVM / traps on WASM -- filed as `.todo/64`). Docs
  steer to user fns/lambdas.

## Wiring points (the usual checklist)

`LispNames` (VALUES + 4); `PackageRegistry` (CL_MACROS + CL_FUNCTIONS;
list-functions count 207 -> 208); `LispEvaluator.evalCons` cases (+ the
floor-family arity branch); `Environment` `values` function;
`Jvm/WasmExprCompiler` cases (+ floor-family branch around the IntConv
compilers); `ScalarWasmCompiler.expandMacro` (mv forms then fail on
`list`/`lambda` as before; the floor-divisor case works); `FreeVarAnalyzer`
both walks (expand-before-walking, flet precedent -- the raw var list would
misread as a call form); `UserMacroExpander.expandAll` +
`LispMacroExpander.rewriteLocalCalls` cases keeping the mv-bind variable list
verbatim; `BuiltinFunctionWrappers` (`values`).

## Pinning tests

- `LispEvaluatorTest`: `evalValuesInSingleValueContext`,
  `evalMultipleValueBind`, `evalMultipleValueBindFloorFamily`,
  `evalFloorFamilyWithDivisorInSingleValueContext`,
  `evalMultipleValueBindGethash`, `evalMultipleValueList`, `evalNthValue`,
  `evalMultipleValueCall`, `evalMultipleValueUserFunctionCollapsesToPrimary`,
  `evalMultipleValueBindErrors`.
- `JvmLispCompilerTest`: `compileAndRunValuesInSingleValueContext`,
  `compileAndRunMultipleValueBind`, `compileAndRunMultipleValueBindGethash`,
  `compileAndRunMultipleValueListAndNthValue`,
  `compileAndRunMultipleValueCall`.
- `WasmLispCompilerIntegrationTest`: `multipleValueForms`,
  `multipleValueGethash`.
- ci-spec: `multiple-values-core`; the `rontolisp-package-introspection`
  expectations gained the 4 macro names and 208 (all three backend test
  classes updated too).
- Test-writing caveats hit while pinning: compiled `print` returns nil
  (interpreter returns the value -- filed as `.todo/63`), and JVM argument
  evaluation order inside one call differs (`.todo/14`) -- side-effect
  assertions go through `setq` in separate top-level forms.
