# The fused tier on Metal

Difficulty: Medium

Carved out of `.todo/629` when the rest of that list was measured and closed. `gemm.cu`
carries nine fused kernels -- the exact GELU and its adjoint, the last-axis softmax and
its adjoint, the last-axis log-softmax and its adjoint, layer-norm's normalization and its
adjoint, and the dropout mask -- and `gemm.metal` carries none: `MetalGemm` answers
`false` to all eighteen entry points, so on an Apple machine each composition runs member
by member, one memory pass per `linalg:` member, as it did before todo-499.

## What that costs

Nothing incorrect -- the decline lands on the internal defun, which IS the chain -- but on
CUDA at the book's shapes those four compositions were 260 ms a step before fusion and 45
after (`.kb/gpu.md`, "The fused tier", batch 32), and the log-softmax pair a further 8 ms
at batch 64.

## Why it is not a port

The row kernels keep one thread per row and fold **sequentially in a `double`
accumulator**, because that is the only order that reproduces the chain's bits, and MSL
has no `double` -- the same wall the resident tier hit here, which it crossed with a
software binary64 (`.kb/gpu.md`, "Precision on this backend"). So the question is not
whether the kernels translate; it is whether a software-binary64 sequential fold over a
row still beats the five-member chain it would replace, on hardware whose per-call floor
is 77 us per COMMAND BUFFER rather than 16-18 us per launch. That is a measurement, and
it can only be made on the hardware.

**Do not land this without a Mac to measure on** -- the same rule `.todo/631` states for
the transposed product, and for the same reason.

## Acceptance

`MetalGpuTest` gains the fused-tier equality (the CUDA suite's
`theFusedTierLandsOnTheComposedDeviceChainsBitsAtBothWidths` at `#f`), the `.kb/gpu.md`
Metal table row changes from "declined", and a measured before/after of a training step on
an Apple machine goes in with it. A member whose fused form LOSES there stays declined and
the measurement is written down, which is a result too.
