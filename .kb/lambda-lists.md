# Lambda list extensions (`&optional`, `&rest`, `&key`, `&aux`, `&allow-other-keys`)

`am.ik.rontolisp.LambdaLists.expand(paramList, body)` -- one desugarer shared by the
interpreter and both compilers -- rewrites every extension into the only native shape,
required symbols plus an optional trailing rest param, via a generated `let*` prologue.
User docs: `doc/en/reference/special-forms/defun.md`, `lambda.md`.

- Unknown keywords signal `Unknown keyword argument: <prin1>` -- a `program-error`, through
  the `%program-error` primitive of [error-handling.md](error-handling.md) ("Argument-shape
  errors") -- unless `&allow-other-keys` is declared or the caller passes
  `:allow-other-keys t`; `&whole` is rejected.
- **The keyword scan is a CALL, not an inline loop.** A keyword parameter binds
  `(%ll-key-cell rest :kw upper)` (the plist cell or nil; `upper` is the upcased twin a
  lowercase-authored keyword also accepts, nil when the spellings coincide) and a function
  runs `(%ll-check-keys rest '(:kw ...))` once (the unknown-keyword / odd-tail signal, the
  `:allow-other-keys` rules in both spellings). Both are ordinary defuns with ONE Lisp body,
  `LambdaLists.runtimeDefun`: `desugarProgram` PREPENDS them to any program that spells
  `&key` anywhere -- quoted data included, because the scan may only over-approximate: the
  `flet`/`labels` and `destructuring-bind` prologues are written while the backend compiles
  a body, long after the pass, and all they have in common is the spelling -- and the
  interpreter evaluates them on the first resolution (`LispEvaluator.resolveFunction`, beside
  the prelude hook), which is the only place that can see a lambda-creation-time expansion
  coming. The names are `PackageRegistry.CL_SYMBOLS` members so a package never qualifies
  them. Before this (2026-09-13) every keyword parameter carried its own `do` loop: the
  hello-clack Worker went 795,062 -> 775,987 B (-2.4%, `--optimize=size`; gzip 209,489 -> 206,264), `zlib` 88,315 ->
  85,687, hello-tiny-routes 839,368 -> 814,808, hello_world/pi_approx unchanged -- and NOT
  the ~10% the census had attributed to the prologue: of its 701 "keyword-loop heads" on
  the Worker, 106 were keyword parameters and 10 the checks; the rest are other
  `do`-over-a-list loops with the same head shape
  ([optimize-dead-code-elimination.md](optimize-dead-code-elimination.md)). Pinned by
  `LambdaListsTest`.
- **Trap**: `&key` with NO key params still switches the tail to keyword convention
  (`Parsed.sawKey`); losing that marker makes the function fixed-arity.
- Helpers use the `__ll_` prefix; `PackageResolver` passes `&`-prefixed symbols through
  unresolved so markers survive in any package.
- **Interpreter**: `LispEvaluator.evalDefun`/`evalLambdaForm` call `expand` at
  lambda-creation time, so runtime-generated lambdas work; `LispLambda.rest` is nullable.
- **Compilers**: `Jvm/WasmLispCompiler.compile` run `LambdaLists.desugarProgram` right
  after `PackageResolver`; extraction points use `LambdaLists.toNative`. Quoted data is
  untouched, so forms headed for the runtime `eval` get NO lambda-list support
  (`doc/en/guides/eval-limitations.md`).

## Variadic calling convention (both compilers)
Physically fixed-arity: required params plus one trailing rest-list param
(`DefunDecl`/`LambdaInfo`/`FunctionInfo`/`WasmFunctionInfo` carry `variadic`), reusing
`TYPE_CALLABLE_BASE + paramCount` -- so the WASM 7-param ceiling allows at most 6
required params for a variadic.

- `compileDirectCall` accepts any argument count; funcall/apply cap at 7.
- In `JvmRuntimeBuilder.buildDispatchMethod` / `WasmRuntimeBuilder.buildDispatchBody` a
  variadic joins every dispatcher of arity >= required, and exact-arity matching EXCLUDES
  variadics.
- Runtime eval registry: arity is `-physicalParamCount`; negative = evaluate ALL arg
  forms (`buildArgList`), non-negative = exactly arity (`buildNArgs`, nil-padded).

## Gaps
- `defmacro` beyond "required + one `&rest`/`&body`" goes through `destructuring-bind`
  wrapping in `LispEvaluator.evalDefmacro` (`.kb/defmacro-backquote.md`); `&environment`
  is MACRO-only (`makeUserMacro`), rejected for functions.
- Runtime `eval`'s own `lambda` (funcId == -1) binds positionally; `--no-gc`
  (`NoGcWasmCompiler.extractDefun`) rejects the keywords; `BuiltinFunctionWrappers` pin
  one arity per builtin.

## Tests
`LambdaListsTest` (the expansion shape and the two helper bodies);
`LispEvaluatorTest#defun{Rest,Optional,Keyword,Aux}`, `#defunEmptyKeySection`;
`JvmLispCompilerTest#compileAndRunDefun{Rest,Optional,KeywordArguments}`;
`WasmLispCompilerIntegrationTest#compileAndRunDefun{RestAndOptional,KeywordArguments}`,
`#compileAndRunVariadicFirstClass`; ci-spec `lambda-list-*`,
`trivia-enablement-language-group`.
