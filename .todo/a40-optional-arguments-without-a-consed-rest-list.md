# Pass an optional argument without consing a rest list

Difficulty: High

Every `&optional` / `&rest` function is physically `(required... &rest rest)`
(`LambdaLists.expand`), so a call that passes an optional argument conses the rest list. On
wasmtime (49.0) that cost grows with the live heap. Measured 2026-09-26 (`.kb/error-handling.md`,
"A built-in's function VALUE"):

| program (wasm Preview 1) | time |
|---|---|
| sort 200,000 fixnums x10 through a variable predicate `(a b)` | 2.4 s |
| the same, predicate `(a &rest r)` | 21.7 s |
| the same, predicate `(a b &rest r)` | 1.5 s |
| `(reduce #'+ data)`, 1,000,000 fixnums x10 (`#'+` is `(&rest r)`) | 5.5 s |
| `(reduce #'logior data)`, same data (binary wrapper) | 1.5 s |

The JVM showed no difference. What it costs today:

- `#'<` and the other comparisons, and `#'logand` / `#'logior` / `#'logxor`, take `(a b &rest r)`
  so a sort predicate's call stays free: `(funcall #'< 1)` and `(apply #'logior '())` are wrong
  counts on the compiled backends, answers on the interpreter (`BuiltinCallArity.STANDARD_WIDER`
  keeps their rows).
- `#'+`, `#'*`, `#'gcd`, `#'lcm`, `#'logeqv`, `#'min`, `#'max`, `#'append`, `#'nconc` cons on
  every call.
- A user function with `&optional` conses whenever the optional argument is passed.

Goal: a callee with optional parameters takes them as parameters -- an unsupplied one a marker
the prologue tests -- on the dispatchers, direct calls, `apply`'s spread and the compiled `eval`,
on both compiled backends and without changing what the interpreter answers. Then give the
comparisons `(a &optional b &rest r)` and the bitwise trio `(&optional a b &rest r)`, and delete
their `STANDARD_WIDER` rows. Measure the table above again.
