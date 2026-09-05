# Lambda list extensions (`&optional`, `&rest`, `&key`, `&aux`, `&allow-other-keys`)

`am.ik.rontolisp.LambdaLists.expand(paramList, body)` -- one desugarer shared by the
interpreter and both compilers -- rewrites every extension into the only native shape,
required symbols plus an optional trailing rest param, via a generated `let*` prologue.
User docs: `doc/en/reference/special-forms/defun.md`, `lambda.md`.

- Unknown keywords signal `Unknown keyword argument: ~a` unless `&allow-other-keys` is
  declared or the caller passes `:allow-other-keys t`; `&whole` is rejected.
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
`LispEvaluatorTest#defun{Rest,Optional,Keyword,Aux}`, `#defunEmptyKeySection`;
`JvmLispCompilerTest#compileAndRunDefun{Rest,Optional,KeywordArguments}`;
`WasmLispCompilerIntegrationTest#compileAndRunDefun{RestAndOptional,KeywordArguments}`,
`#compileAndRunVariadicFirstClass`; ci-spec `lambda-list-*`,
`trivia-enablement-language-group`.
