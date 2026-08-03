# Lambda list extensions (`&optional`, `&rest`, `&key`, `&aux`, `&allow-other-keys`)

User-facing behavior: `doc/en/reference/special-forms/defun.md` (and `lambda.md`);
remaining gaps in `doc/en/guides/missing-features.md`.

## Design: desugar everything to "required + &rest", implement only &rest natively

`am.ik.rontolisp.LambdaLists` is the single parser/desugarer shared by the
interpreter and both compilers. `expand(paramList, body)` parses the full lambda
list and rewrites every extension into the one shape the backends implement
natively — required parameter symbols plus an optional trailing rest parameter —
wrapping the body in a generated `let*` prologue:

- `&optional (o d op)`: `op = (consp <rest>)`, `o = (if op (car <rest>) d)`,
  `<rest> = (if (consp <rest>) (cdr <rest>) nil)` — the default form is evaluated
  only when absent, in `let*` scope (can reference earlier params).
- `&key (k d kp)` / `((:kw k) d kp)`: a `do`/`return` scan over the keyword tail
  (the same stepping shape `getf` expands to) returns the plist cell; `kp`/`k`
  derive from it. Unknown keywords signal `(error "Unknown keyword argument: ~a" ...)`
  unless `&allow-other-keys` is declared or the caller passes `:allow-other-keys t`.
- `&aux (v e)`: plain trailing `let*` bindings.
- `&key` with NO key parameters still switches the tail to keyword convention
  (`Parsed.sawKey`, todo-243): a bare `(x &key &allow-other-keys)` accepts any
  keyword tail, and a bare `(x &key)` consumes the tail and signals on any
  keyword (`:allow-other-keys t` still overrides). The marker used to be lost
  when `keys()` was empty, making the function fixed-arity — trivia's
  `:trivial` optimizer is `(lambda (clauses &key &allow-other-keys) clauses)`
  funcalled with `:types`. Pinned by `LispEvaluatorTest#defunEmptyKeySection` +
  ci-spec `trivia-enablement-language-group`.
- `&whole` is rejected (`Unsupported lambda-list keyword`).

Generated helper variables use the `__ll_` prefix (`__ll_rest`, `__ll_cur`,
`__ll_cell_<name>`), mirroring `__getf_key`. Nested shadowing is safe because the
prologue is ordinary `let*`.

`PackageResolver` passes any symbol starting with `&` through unresolved (like
keywords), so lambda-list markers survive package resolution in any package.

## Where each backend hooks in

- **Interpreter**: `LispEvaluator.evalDefun`/`evalLambdaForm` call
  `LambdaLists.expand` at lambda-creation time (no pre-pass; runtime-generated
  lambdas work too). `LispLambda` gained a nullable `rest` component; the binding
  loop in `LispEvaluator.apply` collects surplus args into the rest list and
  raises real arity errors (too few, or too many for fixed arity — previously an
  IndexOutOfBounds / silent ignore).
- **Compilers**: `Jvm/WasmLispCompiler.compile` run `LambdaLists.desugarProgram`
  right after `PackageResolver` (quoted data is left untouched, so forms headed
  for the runtime `eval` keep their shape — and therefore do NOT get lambda-list
  support; see doc/en/guides/eval-limitations.md). Extraction points
  (`extractSetqLambda`, `Jvm/WasmLambdaCompiler.compileValue/compileCall`) use
  `LambdaLists.toNative`, so compiler unit tests that skip the pre-pass still work.

## Variadic calling convention (both compilers)

A variadic function is physically fixed-arity: required params plus one trailing
rest-list param (`DefunDecl`/`LambdaInfo`/`FunctionInfo`/`WasmFunctionInfo` carry a
`variadic` flag; `paramCount` stays the physical count). No new WASM func types:
the rest list is just another `(ref null eq)` param, so `TYPE_CALLABLE_BASE +
paramCount` is reused. Consequently the WASM 7-param ceiling means at most 6
required params for a variadic function.

- **Direct calls** (`Jvm/WasmFunctionCallCompiler.compileDirectCall`, inline
  lambda calls): compile-time arity check (a real diagnostic now, also for fixed
  arity), evaluate surplus args left-to-right into temps, cons them right-to-left,
  pass the list as the last argument (nil/null when exactly required). Direct call
  sites accept any argument count.
- **funcall dispatch** (`JvmRuntimeBuilder.buildDispatchMethod`,
  `WasmRuntimeBuilder.buildDispatchBody`): a variadic function appears in every
  dispatcher of arity >= required; its case packages args[required..arity) into a
  cons list before the call. Exact-arity matching excludes variadics (funcall of
  R+1 args must yield rest=(list lastArg), not rest=lastArg). funcall/apply are
  therefore capped at 7 actual arguments (dispatchers 0..7), unlike direct calls.
- **Runtime eval registry**: a variadic function's arity is encoded as
  `-physicalParamCount` (JVM `_lookup`, WASM registry blob). The eval call path
  branches on the sign: negative -> evaluate ALL argument forms (`buildArgList`),
  non-negative -> exactly arity (`buildNArgs`, nil-padded). `_apply` needs no
  change (it dispatches by actual list length). WASM pre-registers dispatch
  arities required..7 for variadics when eval is on.

## Out of scope / known gaps

- `defmacro` lambda lists beyond "required + one `&rest`/`&body`" (nested
  patterns, `&optional`, `&key`, ...) are supported via the `destructuring-bind`
  wrapping in `LispEvaluator.evalDefmacro` (see `.kb/defmacro-backquote.md`),
  which reuses this class's tail machinery through
  `LambdaLists.appendTailBindings`; `&whole` stays an error. `&environment` is legal in MACRO lambda lists only: `LispEvaluator.makeUserMacro` strips the pair and binds the parameter to nil around the body BEFORE the destructuring wrap (there is no environment object; nil suffices for the constantp/get-setf-expansion threading idiom), so `LambdaLists.parse` itself still rejects it in function lambda lists.
- The runtime `eval`'s own `lambda` (interpreted closures, funcId == -1) binds
  positionally and does not parse `&` keywords.
- `--no-gc` (`NoGcWasmCompiler.extractDefun`) rejects lambda-list keywords with
  a compile error — the rest list is a cons, which the scalar lowering lacks.
- `BuiltinFunctionWrappers` still pin one arity per builtin (e.g. `#'+` is
  binary); they could now be widened with `&rest` but that shifts every wrapper's
  dispatch-arity membership (see `.todo/031-lambda-list-extensions.md` follow-ups).

Pinning tests: `LispEvaluatorTest#defun{Rest,Optional,Keyword,Aux,...}`,
`JvmLispCompilerTest#compileAndRunDefun{Rest,Optional,KeywordArguments,...}`,
`WasmLispCompilerIntegrationTest#compileAndRunDefun{RestAndOptional,KeywordArguments}`,
`compileAndRunVariadicFirstClass`, and the `lambda-list-*` cases in
`ci-spec.yaml` (all four backends, including `eval` of a variadic call).
