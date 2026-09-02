# `floor`/`truncate` of a large float saturate the quotient at the `long` range and hand back the dividend as the remainder

Difficulty: Medium

Found while closing todo-652 (the `mod`/`rem` zero-remainder sign, 2026-09-02), which had
to compare `rem` against the second value of `truncate` and found that second value is not
trustworthy above 2^63.

Every other numeric operator in rontolisp promotes to a bignum rather than saturating --
`expt`, `*`, `+` and the `logand` family are exact at any magnitude on every backend
(`CLAUDE.md`, `doc/en/guides/math-backends.md`). The float-to-integer rounders do not: the
quotient is computed as a double and narrowed into a `long`, so anything past
`Long.MAX_VALUE` clamps, and the remainder derived from the clamped quotient is then
nonsense -- it comes back EQUAL TO THE DIVIDEND, and `quotient*divisor + remainder =
number`, which CLHS states outright, is off by 1e300.

## The measurement

2026-09-02. All four rontolisp backends -- interpreter (jar and native binary), JVM,
wasm-GC -- print the SAME thing, so this is not drift; the oracle is `/usr/bin/sbcl`.

| form | all four backends | SBCL |
| --- | --- | --- |
| `(truncate 1d300 7.0)` | `(9223372036854775807 1.0e300)` | `(14285714...39008 0.0d0)` (302-digit bignum) |
| `(floor -1d300 7.0)` | `(-9223372036854775808 -1.0e300)` | `(-14285714...39008 0.0d0)` |
| `(truncate 1d30 3.0)` | `(9223372036854775807 9.999999999723299e29)` | `(333333333333333316505293553664 0.0d0)` |
| `(floor 1d300)` | `9223372036854775807` | `10000000000000000525047...60160` |
| `(truncate 1d18 7.0)` | `(142857142857142864 0.0)` | `(142857142857142864 0.0d0)` |

The last row is inside the `long` range and matches SBCL -- and is still not the
mathematical truncate quotient, which is `142857142857142857`: BOTH implementations take
the rounded double `a/b` and round it to an integer, so the quotient is out by 7 and the
remainder by 1.0 (`DREM` says `(rem 1d18 7.0)` is `1.0`; see todo-659 for the wasm half of
that story). Whether to fix that second, subtler half -- an exact quotient wants the exact
`fmod` remainder subtracted before the division is trusted -- is part of this item.

## What to do

`floor`, `ffloor`, `ceiling`, `fceiling`, `truncate`, `ftruncate`, `round` and `fround`,
on all four backends plus `--no-gc`, over a float argument whose magnitude exceeds
`2^53`: produce the exact integer (a `BigInteger` / limb-tier bignum, the way the integer
operators already promote) and derive the remainder from THAT rather than from a clamped
`long`. A finite double IS an exact integer above 2^52, so the conversion is exact and
needs no rounding decision -- `BigDecimal.valueOf(d).toBigIntegerExact()` on the JVM side,
and the wasm side already has the limb tier the `_big_*` helpers use.

Check the one-argument forms as well as the two-argument ones (`(floor 1d300)` above is
the one-argument case and clamps identically), and check `round`'s half-to-even tie at a
magnitude where no tie can exist.

## Acceptance

- Every row above matches SBCL on the interpreter, the JVM, wasm-GC, the component and
  `--no-gc`.
- `quotient*divisor + remainder = number` holds for each of the eight operators over a
  sweep that crosses 2^53 and 2^63, as a unit test rather than only as `ci-spec.yaml`
  rows.
- The `ci-spec.yaml` rows that pin it sit beside
  `mod-rem-zero-is-the-truncate-and-floor-remainder`, which is what made the defect
  visible.
