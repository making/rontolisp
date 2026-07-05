> **Update 2026-07-05 (parse-number e2e):** `/=` shipped (pairwise-different
> expansion over `=`, variadic), plus lite `complex` (zero imaginary part
> only), float-type + computed-type `coerce`, and the predefined
> `*read-default-float-format*` (informational; every float is double).

# Number system extensions (`rational`, `rationalize`, `complex` numbers, `realp`, `complexp`, `realpart`, `imagpart`, `phase`, `conjugate`, `integer-decode-float`, `decode-universal-time`, `encode-universal-time`, `day`, `month`, `year`, `second`, `minute`, `hour`, `timezone`)

**Status:** not implemented. Low priority — complex numbers and time decomposition are niche.

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
| `make-complex` | Construct complex number | — (no complex type) |
| `integer-decode-float` | Decode float into significand, base, exponent | Easy |
| `scale-float` | Scale float by power of radix | Easy |
| `float-radix` | Radix of float type (always 2) | Trivial |
| `most-positive-exponent` | Max exponent | Trivial |
| `most-negative-exponent` | Min exponent | Trivial |
| `most-positive-floating-point` | Max float | Trivial |
| `most-negative-floating-point` | Min float | Trivial |
| `shortest-float` / `float-shortest` | Shortest representation | Medium |
| `long-float` / `single-float` / `double-float` | Float type constructors | Trivial |

### Missing time decomposition

| Function | Purpose | Difficulty |
|----------|---------|------------|
| `decode-universal-time` | Break down universal time | Medium |
| `encode-universal-time` | Build universal time | Medium |
| `day` / `month` / `year` | (Not CL standard; some implementations) | — |

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
3. `float-radix`, `most-positive/exponent`, `most-positive/negative-floating-point` — constants (Trivial).
4. `integer-decode-float`, `scale-float` — IEEE 754 bit manipulation (Easy).
5. Complex numbers — defer until there's a concrete use case.
6. Time decomposition — `decode-universal-time` is useful but requires timezone handling.

### Related

- `[[32-multiple-value-system]]` (`integer-decode-float`, `decode-universal-time` return multiple values)
- `[[35-type-system]]` (`coerce` between number types)
