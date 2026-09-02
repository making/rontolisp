# The linear backward transposes its operands through a copy pass

Difficulty: Medium

Filed 2026-09-02 while closing `.todo/499`. Read `.kb/gpu.md` "The fused tier" first: it
has the per-kernel profile of the book's-shape step this was measured on (batch 32, the
machine's memory shared with another process).

## What the profile says

After the fused tier the largest elementwise bucket that is not a residual add is the
`gather_f32` family: **121 launches a step at the activation shape** (`(32 256 384)`,
16.5 ms of a ~300 ms step), **180 at the per-head shape** (`(32 256 64)`, 3.7 ms) and 6 at
the feed-forward shape (3.7 ms). Every one is `linalg:transpose` with an axes list --
`torch::%t-swap-last` in the matmul adjoints (`g . b^T` and `a^T . g`, one transposed
operand each), and `(torch:transpose key '(0 2 1))` in every attention head -- run as a
strided COPY so that the stacked product can read a contiguous slab. PyTorch's transpose
is a view: its GEMM takes the operand's strides.

## The shape of the fix

`gemm_batched_*` takes one element stride per operand for the BATCH axis and none for the
matrix axes. A kernel that also takes the two matrix strides (row and column) reads a
transposed operand in place: the 16x16 tile walk and the register-tiled loaders index
`A[row * sa_r + k * sa_c]` instead of `A[row * K + k]`, the fold over `k` stays ascending
per cell, and the product is bit-identical to today's -- the same terms in the same
order, only fetched from a different address. The load pattern is what changes: a
transposed `A` is read down its columns, which the shared-memory staging already
tolerates (the tile is loaded once per k-step either way), but measure the t4/t8 tiles at
both orientations before choosing them.

Above the library, `linalg::%la-matmul-nd`'s interceptor would have to recognize a
transposed operand -- which is a copy today, an array the tape holds -- so the honest
seam is a new internal member the two adjoints and the attention head call, e.g.
`%la-matmul-nd-t (a b transpose-a transpose-b)`, whose defun is
`(linalg:matmul (maybe-transpose a) (maybe-transpose b))` and whose device rung passes
strides. Nothing moves on the CPU paths.

## Acceptance

The three gather buckets gone from the profile, the step re-measured, and
`everySingleFloatProductKernelLandsOnTheSameFusedFold` extended to a transposed operand
at both widths.
