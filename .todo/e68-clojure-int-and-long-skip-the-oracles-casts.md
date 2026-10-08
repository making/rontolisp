# e68. Clojure: `int` and `long` skip the oracle's casts

Difficulty: Medium

`(int x)` and `(long x)` lower to `truncate`, so they have none of the oracle's range checks
(`RT.intCast`, `RT.longCast`). Measured 2026-10-08 against clj 1.12.6:

| form | oracle | here |
|---|---|---|
| `(long 1e19)` | `IllegalArgumentException` Value out of range for long: 1.0E19 | `10000000000000000000` |
| `(long 9.223372036854776E18)` | `9223372036854775807` (Java's `(long)` saturates) | `9223372036854775808` |
| `(long 92233720368547758080N)` | `IllegalArgumentException` | the bignum |
| `(long ##NaN)` | `0` | signals (`rounding a non-finite float ...`) |
| `(int 3000000000)` | `ArithmeticException` integer overflow | `3000000000` |
| `(int 1e10)`, `(int ##Inf)` | `IllegalArgumentException` Value out of range for int | the integer / signals |

`clojure.lisp` already has the object casts, `%clojure-long-cast` and `%clojure-int-cast`
(clojure.math's `^long` arguments and `scalb`'s exponent, `vector-of`'s integer kinds), and
`doc/*/clojure/deviations.md` states the current behavior.

## What decides the design

- `int` and `long` are on hot paths (loop counters, `(int (/ n 2))`): a call per cast where
  the inline `truncate` stood costs every program that casts. Measure the bytes (demo.clj,
  the clojure-spec program) and a counting loop's time before and after; keep the inline arm
  where the argument is known to be an integer in range (a literal, `count`).
- The oracle's direct call over a literal double takes `intCast(double)` (`Value out of range
  for int: 1.0E10`), an object `longCast` then `intCast(long)` (`integer overflow`); pick one
  and state the other.

## Plan

1. Route `int`/`long` (call and value) through the casts, folding literal arguments at lower
   time; a clojure-spec case over the table, oracle-identical on all four backends.
2. Update the casts bullet of `doc/*/clojure/deviations.md` and `.kb/clojure-frontend.md`'s
   lowering-table row for `int` `long` `char` `quot`.
