> **Update 2026-07-05 (parse-number e2e):** `/=` shipped (pairwise-different
> expansion over `=`, variadic), plus lite `complex` (zero imaginary part
> only), float-type + computed-type `coerce`, and the predefined
> `*read-default-float-format*` (informational; every float is double).

# Number system extensions (`rational`, `rationalize`, `complex` numbers, `realp`, `complexp`, `realpart`, `imagpart`, `phase`, `conjugate`, `integer-decode-float`, `scale-float`, `float-radix`, `decode-universal-time`, `encode-universal-time`)

**Status:** partially implemented — the lite `complex`, float-type `coerce`,
`/=` and `*read-default-float-format*` shipped 2026-07-05 (see the update
above). The rest is low priority: full complex numbers and time decomposition
are niche.

## What's missing

RontoLisp has the core numeric tower: integers (with `BigInteger` bignum), floats, and rationals (`LispRatio` via `BigInteger` numerator/denominator). Arithmetic (`+`, `-`, `*`, `/`), comparison (`=`, `<`, `>`, `<=`, `>=`), rounding (`truncate`, `floor`, `ceiling`, `round`), modular (`mod`, `rem`), absolute (`abs`), root (`sqrt`, `isqrt`), power (`expt`, `exp`, `log`), trigonometric (`sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `sinh`, `cosh`, `tanh`), number theory (`gcd`, `lcm`), sign (`signum`), and predicates (`numberp`, `integerp`, `floatp`, `rationalp`, `zerop`, `plusp`, `minusp`, `evenp`, `oddp`).

### Missing numeric functions

| Function | Purpose | Difficulty |
|----------|---------|------------|
| `rational` | Exact rational from float: `(rational 1.5)` -> `3/2` | Easy |
| `rationalize` | Simplest rational within tolerance: `(rationalize 1.4999999 0.01)` -> `3/2` | Medium |
| `realp` | True for real numbers (always t without complex) | Trivial |
| `complexp` | Complex number predicate | — (no complex type) |
| `realpart` / `imagpart` | Complex accessors | — (no complex type) |
| `conjugate` | Complex conjugate | — (no complex type) |
| `phase` | Complex phase angle | — (no complex type) |
| `integer-decode-float` | Decode float into significand, base, exponent | Easy |
| `scale-float` | Scale float by power of radix | Easy |
| `float-radix` | Radix of float type (always 2) | Trivial |
| `float-digits` | Significand digits of a float | Trivial |
| `float-sign` | Sign of a float, as a float | Trivial |
| `most-positive-double-float` | Largest representable double | Trivial |
| `most-negative-double-float` | Most negative representable double | Trivial |

### Missing time decomposition

`get-universal-time` already exists (see `.kb/time-environment-builtins.md`);
only the decomposition/composition pair is missing.

| Function | Purpose | Difficulty |
|----------|---------|------------|
| `decode-universal-time` | Break down universal time | Medium |
| `encode-universal-time` | Build universal time | Medium |

### Complex numbers

CL has a full complex number tower. RontoLisp does not. Implementing complex numbers requires:
- A `LispComplex` type (real + imaginary parts).
- Updating all arithmetic operators to handle complex operands.
- New predicates and accessors.
- JVM: represent as `Object[]` or dedicated class.
- WASM GC: a struct type.
- WASM scalar: no complex support (scalar backend is for pure numeric exports).

This is a significant undertaking with limited ROI for the typical use cases RontoLisp targets.

### Implementation approach (pragmatic subset)

1. `rational` — convert float to exact ratio (Easy, useful).
2. `realp` — always true for existing types (Trivial).
3. `float-radix`, `float-digits`, `most-positive-double-float`, `most-negative-double-float` — constants (Trivial).
4. `integer-decode-float`, `scale-float`, `float-sign` — IEEE 754 bit manipulation (Easy).
5. Complex numbers — defer until there's a concrete use case.
6. Time decomposition — `decode-universal-time` is useful but requires timezone handling.

### Related

- `[[032-multiple-value-system]]` (`integer-decode-float`, `decode-universal-time` return multiple values)
- `[[035-type-system]]` (`coerce` between number types)
