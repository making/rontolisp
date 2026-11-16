# j59. `(floor a b)` of two integers divides into a ratio first, on every backend

Difficulty: Medium

`.kb/starlark-frontend.md`, "Against CPython (2026-11-16)": Starlark's `//` was lowered to
`(values (floor a b))`, and 40% of `mixed.star`'s samples on the JVM were `BigInteger`
(`multiplyByInt`, `binaryGcd`, `divideKnuth` under `_rat`). The two-argument forms of
`floor`, `truncate`, `ceiling` and `round` are `(floor (/ a b))`
(`LispMacroExpander.expandFloorFamilyDivisor`): a ratio is made, with its gcd, and then
rounded. Measured there, ns per call of a function over a parameter (JDK 25, wasmtime 49):

| form | JVM | wasm |
|---|---|---|
| `(values (floor x 2))` | 142 | 59 |
| `(ash x -1)` | 39 | 17 |
| `(values (floor x 7))` | 228 | 66 |
| `(values (truncate x 7))` | 170 | 62 |
| `(/ (- x (mod x 7)) 7)` | 122 | 49 |
| `(mod x 7)` in a counting loop, fused | 9 | 21 |

The Starlark lowering no longer spells `floor` (a shift by a power of two, else the exact
division of the dividend less its remainder); a Common Lisp program that divides integers
with `floor` or `truncate` still pays this.

## Do

- An integer arm ahead of the ratio: two fixnums are `Math.floorDiv` / `floorMod` on the
  JVM and one `i64.div_s` with the sign fix on wasm, two integers of any size the bignum
  quotient and remainder, with no ratio made. The second value is the remainder the same
  division answers (`.kb/multiple-values.md`).
- Whether the integer fusions take `floor` and `truncate` of two integers as they take
  `mod` and `rem` (`.kb/jvm-int-fusion.md`, `.kb/wasm-int-fusion.md`): measure the table
  above again with each.
- ci-spec cases for the four rounding functions over fixnums, bignums, negative operands
  and a zero divisor, the values and the report (`.kb/error-handling.md`).
