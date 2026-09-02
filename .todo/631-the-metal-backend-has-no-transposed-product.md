# The Metal backend has no transposed product

Difficulty: Medium

Filed 2026-09-02 alongside the CUDA transposed product (`.kb/gpu.md`, "The transposed
product"). `gemm.cu` grew two flags, `ta` and `tb`, that let the stacked kernel read an
operand stored with its last two axes exchanged; `gemm.metal` did not, and
`MetalGemm.gemmT` answers `false` at any orientation but the plain one.

## What that costs

Nothing incorrect -- the decline lands on the portable defun, which transposes through a
copy exactly as every backend did before -- but on an Apple machine the two matmul
adjoints still pay a full strided pass over the activation per backward call. On the CUDA
side that pass was 53.5 ms of a 639 ms step at the book's shapes.

## The shape of the fix

The MSL kernel's staging is the CUDA one's; the change is the same change, and the
argument for it is already written down: the tile the fold reads is identical, so the
product stays bit-identical to the plain product of the transposed copy, and only the
address the staging load comes from moves. What is NOT transferable is the measurement --
Metal's per-call floor is 77 us per COMMAND BUFFER rather than 16-18 us per launch, and
`gemm.metal` computes in `float` where `gemm.cu` computes in `double`, so whether the
saved pass pays has to be measured on the hardware rather than inherited.

**Do not land this without a Mac to measure on.** An untested kernel change on a backend
whose test suite cannot run here is worth less than the honest decline it would replace.

## Acceptance

`MetalGpuTest` gains the transposed-product equality (the CUDA suite's
`aTransposedOperandIsReadInPlaceAndFoldsOntoTheUntransposedProduct...` at `#f`), the
`.kb/gpu.md` Metal table row changes from "declined", and a measured before/after of the
step on an Apple machine goes in with it.
