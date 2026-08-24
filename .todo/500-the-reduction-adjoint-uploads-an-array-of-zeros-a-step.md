# The reduction adjoint uploads an array of zeros, and drains the pipeline to do it

Difficulty: Medium

Filed 2026-08-24 while closing `.todo/497`. Read `.kb/gpu.md` "The launch pipeline, and what a
step is actually bound by" first, whose opened pipeline this closes 90 times a batch.
The chapter-2 profile it was filed off is in the git history of that file.

## What the profile found

At the book's chapter-2 shapes (`d_model` 512, 6 blocks, 8 heads, batch 64) the training
step is **0.37 s of which 0.217 s is device kernel time**: unlike chapter 3, the device
is idle about 40% of the batch. There is no host READ to blame -- 4 `cuMemcpyDtoH` a step,
0.13 ms, so lazy results hold -- what the step has instead is a host WRITE: **104
`cuMemcpyHtoD` a step, ~190 MB**, and every one of them first drains the launch queue
(`CudaGemm.upload` -> `awaitQueued`), which is what the 102 `cuCtxSynchronize` a step are.

Ninety of those uploads, ~186 MB of the 190, have one caller:

```lisp
(defun torch::%t-grad-bcast (g x ax)
  (let ((gk (torch::%t-keepdims g x ax)))
    (linalg:add (linalg:zeros-like x) gk)))     ; torch.lisp
```

`linalg:zeros-like x` allocates a FRESH host array at the activation shape (2.1 MB at
these shapes) purely so that `linalg:add`'s broadcast will stretch `gk` back over it. The
device add then has to stage that operand up -- an array of zeros, uploaded 90 times a
batch, each upload closing the pipeline `.todo/496` opened.

## The shape of the fix

`linalg::%la-broadcast-to` already exists and is exactly this operation without the zeros:
it lowers to `%la-gather-strided`, which is a device member over a resident operand, so
the adjoint becomes one device pass with no host operand at all. The same two lines are
what `torch:mean`'s adjoint divides (`torch.lisp` line ~748), and `%t-grad-reshape`'s
scalar branch is the same pattern.

Two things to settle before it is a one-line change:

- **Bit-identity.** `0.0 + v` is `v` for every `v` except `-0.0` (the add answers `+0.0`),
  so the replacement is bit-identical everywhere a gradient is not a negative zero and
  differs there. Decide whether the adjoint should keep normalizing `-0.0`, and pin it.
- **It is a `torch.lisp` change, so it moves all four backends** -- the interpreter, the
  JVM and both wasm outputs run the same source. The chapter-2 and chapter-3 outputs and
  `ci-spec.yaml` have to be re-read, not just the GPU numbers.

## Acceptance

The adjoint no longer allocates or uploads a zero array; the chapter-2 book-shape step
re-measured (uploads a step, `cuCtxSynchronize` a step, device-busy share, wall) and the
chapter-3 step re-read for the same reason; four-backend output unchanged or its move
explained and re-pinned.
