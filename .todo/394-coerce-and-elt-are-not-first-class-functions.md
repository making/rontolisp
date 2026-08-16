# 394. #'coerce and #'elt are not first-class functions

Difficulty: Low

Found while converting examples to rove (`.todo/392`). Both are ordinary CL
FUNCTIONS, but both are implemented only as operator-position cases
(`LispEvaluator.evalCons`, `Jvm/WasmExprCompiler.compileCons`) with no
`Environment` definition and no `BuiltinFunctionWrappers` entry, so a
first-class reference fails at run time on every backend:

```lisp
(funcall #'coerce #("a" "b") 'list)   ; The function COERCE is undefined
(funcall #'elt '(1 2 3) 1)            ; The function ELT is undefined
```

## Why it matters

Not just `mapcar`: rove's `form-inspect` rewrites every non-macro, non-special
form inside an `ok` into `(apply #'op args)` so it can report each argument's
value, so ANY assertion mentioning one of these dies as
`Raise an error while testing.` -- `(ok (equal (coerce x 'list) y))` is an
ordinary thing to write. `examples/cloudflare-workers/httpbin/check.lisp` hit
it and was rewritten around `aref`, which does have a wrapper.

## The work

- `elt` is a plain 2-arity wrapper: `(lambda (sequence index) (elt sequence index))`.
- `coerce`'s second argument is a TYPE SPECIFIER the operator reads statically,
  which is why it is an operator case. The wrapper must therefore dispatch on
  the runtime designator over the literal shapes the operator supports -- the
  `concatenateWrapper` result-type-family pattern, `.kb/core-representation.md`
  "Built-in function wrappers" + `.kb/concatenate-result-families.md`. The
  compile-time fold (`PureBuiltinFolder`) and the operator case stay as they
  are; the wrapper is only the value form.
- Both need the `Environment.createGlobal()` half too, or the interpreter and
  the native image still answer "undefined".
- SWEEP, not two fixes: every operator-position case in `evalCons` that names a
  CL FUNCTION (not a macro or special operator) and has no wrapper is the same
  bug. Enumerate them -- `PackageRegistry.CL_SYMBOLS` minus the wrapper table
  minus the special operators is a good first cut -- and pin the survivors with
  one table-driven test that `funcall`s every CL function name with its
  arity-shaped arguments.
