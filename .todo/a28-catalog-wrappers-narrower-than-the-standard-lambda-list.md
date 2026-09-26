# Built-in function values are narrower than the operator's standard lambda list

Difficulty: Medium

`BuiltinCallArity.STANDARD_WIDER` lists the catalog wrappers (`BuiltinFunctionWrappers`) whose
lambda list takes fewer counts than the operator's standard one. A direct call uses the wider
shape; a function VALUE still uses the wrapper's:

| form | interpreter | JVM / wasm (2026-09-26) |
|---|---|---|
| `(funcall #'< 1 2 3)` | `T` | `< expects 2 arguments, got 3` |
| `(apply #'logand '(1 3 7))` | `1` | `LOGAND expects 2 arguments, got 3` |
| `(funcall #'string-upcase "abc" :start 1)` | `STRING-UPCASE expects 1 argument, got 3` | same |

The same wrappers are what a compiled `eval` reaches, so `(eval '(< 1 2 3))` is covered only by
the comparison chain in `_eval` (`.kb/eval-runtime.md`, "Argument counts").

Goal: widen each wrapper to its standard lambda list and delete its `STANDARD_WIDER` row (the
class refuses a row that is no longer wider). The comparisons are binary on purpose -- a variadic
wrapper conses a rest list per sort-predicate call -- so they need a shape that keeps the binary
call free (a two-required-plus-rest lambda list whose rest is nil on the hot path, measured).
Pin the function-value twins in `ci-spec.yaml`.
