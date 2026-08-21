# `abs` and unary minus lose the sign of zero on both WASM backends

Difficulty: Medium

Found while intercepting `linalg:erf` for `--simd` (todo-468, 2026-08-21), which is
how it surfaced: the kernel had to be bit-identical to the defun on each backend,
and on wasm the defun answers something the other two do not. The kernel was
written to match its own backend, so todo-468 did not introduce this and does not
carry it -- but it is now written down in `.kb/linalg-simd.md` as a divergence
rather than as a bug, which it is.

## The case

Both operators go through the generic runtime path when their argument is a
VARIABLE, because there is no float literal in the argument form to lower against.
That path is wrong for zero at both ends:

- `abs` is emitted as `_rat_cmp`'s float compare, `x < 0 ? 0 - x : x`. For `-0.0`
  the comparison is false, so `-0.0` is returned unchanged.
- unary minus is emitted as `_rat_sub(0, x)`. For `+0.0` that is `0 - 0 = +0.0`,
  where IEEE negation gives `-0.0`.

Verified on this repository at `a671f569`, with
`(defun f (x) (list (abs x) (- x)))`:

| call | interpreter | JVM | wasm-GC | component |
| --- | --- | --- | --- | --- |
| `(f -0.0)` | `(0.0 0.0)` | `(0.0 0.0)` | **`(-0.0 0.0)`** | **`(-0.0 0.0)`** |
| `(f 0.0)` | `(0.0 -0.0)` | `(0.0 -0.0)` | **`(0.0 0.0)`** | **`(0.0 0.0)`** |

The LITERAL path is already correct and agrees everywhere -- `(list (abs -0.0)
(- 0.0) (- -0.0))` prints `(0.0 -0.0 0.0)` on all four backends -- so this is the
variable path only, and a test written with literals cannot see it.

`Math.abs` folds `-0.0` to `+0.0` and the interpreter and the JVM backend both use
it; `0.0 - x` is the one spelling that does not, and it is the one wasm emits.

## Why it matters beyond the printed sign

`-0.0` and `0.0` are `=` and print differently, so the divergence is invisible to
arithmetic and visible to output -- which is exactly the shape of defect
`ci-spec.yaml` exists to catch and did not, because no case passes a signed zero
through a variable. It also propagates: any `linalg:` member built over `abs` or
unary minus inherits it elementwise, which is how `linalg:erf` acquired it.

## The precedent

todo-108 fixed the sibling defect for COMPARISONS (`>` on NaN and on `-0.0`
against `0.0` differed per backend, and `LinalgSimdKernels` had to mirror each
backend until it was fixed). The shape of the fix there is the shape of the fix
here: correct the scalar operator on the backend that is wrong, then delete the
per-backend mirroring the kernels carry. See `.kb/linalg-simd.md`, "Comparisons".

## What to do

1. Emit `abs` on the variable path as a sign-bit clear (`f64.abs` / `f32.abs` are
   single instructions in core wasm and are exactly `Math.abs`), not as a compare
   and subtract. Same for unary minus: `f64.neg` / `f32.neg`, not `_rat_sub(0, x)`.
   Both are in the `--no-gc` backend's reach as well, so check whether that one
   spells them the same way.
2. The generic path has to keep working for a non-float argument (a rational, an
   integer, a boxed value), so the float instruction can only be taken on the
   branch that has already established the operand is a float -- that is what
   `_rat_cmp` / `_rat_sub` are dispatching on today.
3. Then delete the `-0.0` paragraph in `.kb/linalg-simd.md` (`linalg:erf`'s
   backend-specific spellings) and simplify the wasm `erf` kernel to whatever the
   fixed operators allow.
4. Add a `ci-spec.yaml` case that passes a signed zero through a variable, since
   the whole reason this lived so long is that nothing did.

## Acceptance

- The table above reads identically across all four backends.
- `(linalg:erf #d(-0.0))` is `#d(0.0)` everywhere, with and without `--simd`.
- The existing four-backend suites stay byte-identical otherwise: `-0.0` is `=`
  to `0.0`, so no arithmetic result may move, only printed signs.
