# wasm `mod`/`rem` synthesize the remainder in f64, so they diverge on large magnitudes and infinite divisors

Difficulty: Medium

Found while closing todo-652 (the zero-remainder sign, 2026-09-02). That item settled the
SIGN of a zero on all five paths and left these two rows alone deliberately: they are
arithmetic divergences, not printed signs, and each wants its own decision.

The interpreter and the JVM compute the float remainder with Java's `%` / `DREM`, which is
the EXACT remainder at any magnitude. Both wasm compilers instead synthesize
`a - b*(floor|trunc)(a/b)` in f64 -- `WasmRatioRuntimeBuilder.buildRatRemBody`'s float arm
and `NoGcWasmCompiler.compileModRem`'s -- and that formula rounds.

## The measurement

2026-09-02, one tree, every value through a `defun` parameter. `inf` = `(/ 1.0 0.0)`.

| form | interpreter / JVM | wasm-GC / component / `--no-gc` | SBCL |
| --- | --- | --- | --- |
| `(rem 1d18 7.0)` | **`1.0`** | **`0.0`** | `0.0` |
| `(rem 1d300 7.0)` | **`1.0`** | **`0.0`** | `0.0` |
| `(mod -1d300 7.0)` | **`6.0`** | **`-0.0`** | `0.0` |
| `(rem 3.0 inf)` | **`3.0`** | **`NaN`** | signals |
| `(mod 3.0 inf)` | **`3.0`** | **`NaN`** | signals |
| `(mod -3.0 inf)` | **`Infinity`** | **`NaN`** | signals |
| `(mod 3.0 -inf)` | **`-Infinity`** | **`NaN`** | signals |
| `(rem -0.0 inf)` | **`-0.0`** | **`NaN`** | signals |
| `(rem inf 3.0)` | `NaN` | `NaN` | signals |
| `(rem 1.0 0.0)` | `NaN` | `NaN` | signals `division-by-zero` |

Two separate causes:

- **Large magnitudes.** `q = trunc(a/b)` is a double, and above 2^53 it is not the exact
  integer quotient, so `b*q` is not `a` and the subtraction returns whatever is left.
  `1d18` really is `1 (mod 7)` (`10^6 = 1 (mod 7)`, so `10^18 = 1`), which is what `DREM`
  answers. **SBCL is wrong here too** and for the same reason -- it evaluates the CLHS
  formula with a bignum quotient and then rounds `7.0*q` back to `1d18` -- so it is not
  the oracle for this row (`.kb/linalg-simd.md`, `mod`/`rem`).
- **An infinite divisor.** `a/b` is a zero, `trunc` keeps it, and `inf * 0.0` is `NaN`, so
  the whole expression is `NaN`. `DREM` answers the dividend, which is the mathematical
  value of `a - b*q` with `q = 0`, and CL's `mod` then adds the divisor when the signs
  differ, giving the `±Infinity` rows. SBCL signals on every infinite operand, so there is
  no upstream oracle -- decide on the arithmetic.

## What to do

Make both wasm compilers compute the same remainder the interpreter and the JVM do. The
cheap half is the infinite divisor: guard `|b| = inf` and answer `a` (`rem`), or `a` /
`a + b` by sign (`mod`), which is exactly what `DREM` + the divisor-sign correction gives.
The large-magnitude half needs a real `fmod`: the textbook loop scales `b` up by powers of
two while `|scaled| <= |a|`, subtracts where it fits and halves back down, which is exact
in f64 and terminates in at most `exponent(a) - exponent(b)` steps. It is the same body in
both compilers, so put it where the two can share it rather than writing it twice.

Watch the SIGN of the zero while you do: todo-652's answer -- `-0.0` only when the dividend
is `-0.0` and the divisor is positive -- must survive, and today it comes out of the
`+0.0` added to the quotient, which an exact `fmod` loop would no longer have.

## Acceptance

- Every row above identical on the interpreter, the JVM, wasm-GC, the component and
  `--no-gc`, with the interpreter/JVM column as the answer.
- The rows are in `ci-spec.yaml`, beside
  `mod-rem-zero-is-the-truncate-and-floor-remainder`.
- `.kb/linalg-simd.md`'s `mod`/`rem` bullet loses the paragraph that records these two as
  known divergences.
