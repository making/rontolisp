# Layer-norm's affine needs a two-output member

Difficulty: Medium

Filed 2026-09-02 while closing `.todo/629`, where this member was measured and NOT built.
`.kb/gpu.md` "The chains left composed" has the numbers; this is why they did not pay as
they stand.

## The measurement

`torch::%m-layer-norm-forward` ends in `(torch:add (torch:mul norm weight) bias)`, three
tape nodes over the one fused `%la-layer-norm`. At the book's shapes, batch 64, 13
layer-norms a step, that affine is **about 15 ms a step**:

| | per call, `(64 256 384)` |
|---|---|
| forward `mul` broadcast over `(384)` | 0.213 ms |
| forward `add` broadcast | 0.214 ms |
| backward `g * weight` broadcast | 0.215 ms |
| backward `g * norm` zip | 0.314 ms |
| the two axis-0 folds per parameter | 0.098 ms each |

## Why fusing it gives half of it back

Folding the affine into `%la-layer-norm` (and `weight` into `%la-layer-norm-grad`) removes
the three broadcast passes -- about 8 ms a step, free, since `weight` and `bias` are 384
floats that stay in cache. But the WEIGHT's gradient is the axis-0 fold of `g * norm`, and
once the affine is inside the node the node's output is `norm * w + b`, so `norm` is no
longer stored anywhere. A separate `%la-layer-norm-gn` member has to recompute the row
statistics -- two more passes over `x` -- and hands back about 3 of the 8.

**The honest fix is an adjoint member that emits TWO arrays**, `dx` and `g * norm`, from
the one pass that already computes the row statistics: 0.107 ms of extra write against the
0.314 zip it replaces. No `linalg:` member answers two arrays today, and the shape has to
survive the JVM call-shape lowering (`LinalgKernelCallLayout`) and the interpreter's
`define`, not just the interpreter.

## What else moves

It is a `torch.lisp` change to the node structure of the most-used module in the library:
three nodes become one, with three parents, and `weight` / `bias` gradients that must
equal what `%t-unbroadcast` produces today, fold order included. `TorchGradcheck`'s
`FUSED_PROGRAM` already compares `torch:layer-norm` against the hand-written composition
on three backends and `ci-spec.yaml`'s `torch-fused-compositions` on all four -- both must
still print `T`.

## Acceptance

The `bcast_f32` bucket at the activation shape down by the 39 launches a step the affine
owns, the step re-measured the long way (three interleaved rounds of `(t23 - t3) / 20`),
four-backend output unchanged, and the two-output call shape documented in
`.kb/linalg.md`.
