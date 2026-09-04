# Lambda list extensions (`&optional`, `&rest`, `&key`, `&aux`, `&allow-other-keys`)

User docs: `doc/en/reference/special-forms/defun.md`, `lambda.md`; gaps in
`doc/en/guides/missing-features.md`.

## Design

`am.ik.rontolisp.LambdaLists.expand(paramList, body)` -- one parser/desugarer shared by
the interpreter and both compilers -- rewrites every extension into the only native
shape, required symbols plus an optional trailing rest param, via a generated `let*`
prologue popping `<rest>`.

- `&optional (o d op)`: `op = (consp <rest>)`, `o = (if op (car <rest>) d)`; default
  evaluated only when absent, in `let*` scope (may reference earlier params).
- `&key (k d kp)` / `((:kw k) d kp)`: a `do`/`return` scan over the keyword tail (the
  shape `getf` expands to) yields the plist cell. Unknown keywords signal
  `(error "Unknown keyword argument: ~a" ...)` unless `&allow-other-keys` is declared or
  the caller passes `:allow-other-keys t`.
- `&aux (v e)`: trailing `let*` bindings. `&whole` is rejected
  (`Unsupported lambda-list keyword`).
- `&key` with NO key params still switches the tail to keyword convention
  (`Parsed.sawKey`). Trap: losing that marker when `keys()` is empty makes the function
  fixed-arity.
- Helpers use the `__ll_` prefix (`__ll_rest`, `__ll_cur`, `__ll_cell_<name>`).
  `PackageResolver` passes `&`-prefixed symbols through unresolved so markers survive in
  any package.

## Backend hooks

- **Interpreter**: `LispEvaluator.evalDefun`/`evalLambdaForm` call `expand` at
  lambda-creation time (no pre-pass, so runtime-generated lambdas work). `LispLambda` has
  a nullable `rest`; `LispEvaluator.apply` collects surplus args and raises arity errors.
- **Compilers**: `Jvm/WasmLispCompiler.compile` run `LambdaLists.desugarProgram` right
  after `PackageResolver`. Quoted data is untouched, so forms headed for the runtime
  `eval` get NO lambda-list support (doc/en/guides/eval-limitations.md). Extraction points
  (`extractSetqLambda`, `Jvm/WasmLambdaCompiler.compileValue/compileCall`) use
  `LambdaLists.toNative` so tests skipping the pre-pass still work.

## Variadic calling convention (both compilers)

Physically fixed-arity: required params plus one trailing rest-list param
(`DefunDecl`/`LambdaInfo`/`FunctionInfo`/`WasmFunctionInfo` carry `variadic`;
`paramCount` stays physical). The rest list is another `(ref null eq)`, reusing
`TYPE_CALLABLE_BASE + paramCount` -- so the WASM 7-param ceiling allows at most 6
required params for a variadic.

- Direct calls (`Jvm/WasmFunctionCallCompiler.compileDirectCall`): compile-time arity
  check; surplus args into temps left-to-right, consed right-to-left, list passed last
  (nil/null when exactly required). Any argument count accepted.
- funcall dispatch (`JvmRuntimeBuilder.buildDispatchMethod`,
  `WasmRuntimeBuilder.buildDispatchBody`): a variadic joins every dispatcher of arity >=
  required, packaging `args[required..arity)` into a list. Exact-arity matching EXCLUDES
  variadics (funcall of R+1 args must give `rest=(list lastArg)`). funcall/apply cap at 7
  actual arguments (dispatchers 0..7); direct calls do not.
- Runtime eval registry: arity encoded as `-physicalParamCount` (JVM `_lookup`, WASM
  registry blob); negative = evaluate ALL arg forms (`buildArgList`), non-negative =
  exactly arity (`buildNArgs`, nil-padded). `_apply` unchanged (dispatches by list
  length). WASM pre-registers dispatch arities required..7 for variadics when eval is on.

## Gaps

- `defmacro` lambda lists beyond "required + one `&rest`/`&body`" go through the
  `destructuring-bind` wrapping in `LispEvaluator.evalDefmacro`
  (`.kb/defmacro-backquote.md`), reusing `LambdaLists.appendTailBindings`; `&whole` stays
  an error. `&environment` is MACRO-only: `LispEvaluator.makeUserMacro` strips the pair
  and binds the parameter to nil around the body BEFORE the destructuring wrap (no
  environment object exists); `LambdaLists.parse` still rejects it for functions.
- Runtime `eval`'s own `lambda` (interpreted closures, funcId == -1) binds positionally,
  no `&` keywords.
- `--no-gc` (`NoGcWasmCompiler.extractDefun`) rejects lambda-list keywords: the rest list
  is a cons, absent from the scalar lowering.
- `BuiltinFunctionWrappers` pin one arity per builtin (`#'+` is binary); widening with
  `&rest` would shift every wrapper's dispatch-arity membership.

## Tests

- `LispEvaluatorTest#defun{Rest,Optional,Keyword,Aux,...}`, `#defunEmptyKeySection`
- `JvmLispCompilerTest#compileAndRunDefun{Rest,Optional,KeywordArguments,...}`
- `WasmLispCompilerIntegrationTest#compileAndRunDefun{RestAndOptional,KeywordArguments}`,
  `#compileAndRunVariadicFirstClass`
- ci-spec `lambda-list-*` (four backends, incl. `eval` of a variadic call),
  `trivia-enablement-language-group`
