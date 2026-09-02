# `mod`/`rem` on a zero remainder follow IEEE `fmod`, where CLHS reads as an integer quotient

Difficulty: Medium

Found while closing todo-648 (the signed-zero convergence, 2026-09-02). All four backends
now AGREE with each other on this, so nothing here is drift -- the question is whether the
answer they agree on is the right one. It differs from SBCL.

## The measurement

`/usr/bin/sbcl` as the oracle, against all four rontolisp backends built from one tree
(2026-09-02). Every value reaches the operator through a `defun` parameter.

| form | rontolisp (all four) | SBCL |
| --- | --- | --- |
| `(rem -0.0 2.0)` | `-0.0` | `-0.0` |
| `(rem 0.0 2.0)` | `0.0` | `0.0` |
| `(rem -0.0 -2.0)` | **`-0.0`** | `0.0` |
| `(rem -4.0 2.0)` | **`-0.0`** | `0.0` |
| `(rem 4.0 -2.0)` | `0.0` | `0.0` |
| `(mod -0.0 2.0)` | `-0.0` | `-0.0` |
| `(mod -0.0 -2.0)` | **`-0.0`** | `0.0` |
| `(mod -4.0 2.0)` | **`-0.0`** | `0.0` |

Only the ZERO results differ, and only in their sign; `-0.0` is `=` to `0.0`, so no
arithmetic moves either way. The row todo-648 was chartered to fix -- `(mod -0.0 2.0)` /
`(rem -0.0 2.0)` -- is one all five agree on, which is why 648 converged onto the
interpreter/JVM answer and stopped there.

## Why they differ

rontolisp computes IEEE `fmod`: the interpreter and the JVM backend use Java's `%`
(`DREM`), and wasm synthesizes `a - b*floor|trunc(a/b)` in f64 and re-signs a zero result
from the dividend to match. IEEE says a zero remainder carries the sign of the DIVIDEND,
whatever the divisor.

SBCL computes CLHS's own words. `(rem number divisor)` is defined as the second value of
`(truncate number divisor)`, i.e. `number - divisor*q` where **`q` is an exact INTEGER**.
An integer `q` of zero is `+0`, not `-0.0`, so `(rem -4.0 2.0)` is `-4.0 - 2.0*(-2)` =
`-4.0 + 4.0` = `+0.0`, and `(rem -0.0 -2.0)` is `-0.0 - (-2.0 * 0)` = `-0.0 - (-0.0)` =
`+0.0`. The sign of the zero falls out of the arithmetic rather than being imposed.

Both readings are defensible and neither is obviously what a user wants. CLHS does not
discuss signed zeros at all, so "follow the spec literally" is a claim about a text that
was written before the question existed.

## What to do

Decide, then make all four backends say it -- they agree today, so whichever way this
goes it is a four-backend change, not a convergence.

- Keeping IEEE `fmod` costs nothing and is what every C-family `%` does. Write the
  reasoning into `.kb/linalg-simd.md` beside the table 648 left there, and add the
  differing rows to `ci-spec.yaml` so the agreement is pinned.
- Following CLHS/SBCL means the zero result takes the sign of `a - b*q` with an exact
  integer `q`. On the interpreter and the JVM that is no longer Java's `%`; on wasm the
  `f64.copysign` re-signing 648 added to `WasmRatioRuntimeBuilder.buildRatRemBody` (and
  the `--no-gc` copy in `NoGcWasmCompiler.compileModRem`) would be replaced rather than
  removed, since the raw cancellation is not the SBCL answer either.

Check `(mod x 0.0)`, infinities and NaN divisors before committing to either -- the sweep
above only covered finite operands.

## Acceptance

- One reading, stated in `.kb/` with the SBCL numbers beside it and dated, and the same
  answer on the interpreter, the JVM, wasm-GC, the component and `--no-gc`.
- The rows that move are in `ci-spec.yaml`.
- No arithmetic result moves: only printed signs may change.
