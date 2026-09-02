# The Metal layer-norm affine is declined unmeasured

Difficulty: Medium

todo-634 folded `torch:layer-norm`'s `* weight + bias` into the normalization as a
two-output member (`.kb/gpu.md`, "Layer-norm's affine") and built the kernels in
`gemm.cu` only. `MetalGemm.layerNormAffineF` / `layerNormAffineGradF` return `false`, and
the comment says so plainly: "the kernels are `gemm.cu`'s only, and whether the fold pays
on this backend is for a measurement on a Mac". This item is that measurement.

## Why it is likely to pay MORE here, and why that is not a reason to skip measuring

The same asymmetry todo-636 and todo-643 both measured: a Metal call is `commit` plus
`waitUntilCompleted` and nothing overlaps, so removing a member removes a full wait as
well as a memory pass. todo-643 predicted 2.3% (CUDA's figure) and measured 15%, but for a
reason nobody predicted -- a member of the chain was not on the device at all. Read the
Metal side of the chain before assuming the CUDA ratio transfers:
`%la-layer-norm-affine`'s two extra members are broadcast multiplies against a `(len)`
operand, and this backend's broadcast threshold is `MIN_STRIDED_ELEMENTS` (2^18), which
the book's `(16384 384)` activation clears -- so unlike todo-643's mask, they probably ARE
device members today.

## The shape of the work

- Eight of `gemm.cu`'s eleven fused members are already in `gemm.metal`; this adds the
  affine pair. Its adjoint answers TWO arrays, which no Metal fused member does yet --
  `rowMember` binds one result -- so `MetalGemm` needs the two-destination shape
  `CudaGemm` grew for it.
- The boundaries are the fused tier's: both operands floats takes `bin_op_exact`, an
  operand the float grid does not hold takes the software binary64 route
  (`gemm.metal`, "THE FUSED TIER"). `MetalGpuTest.theFusedTierLandsOnTheComposedDeviceChainsBits`
  is where the equality assertion goes.
- todo-644 is about the same member's adjoint recomputing the normalization; read it
  first, because what it changes is what this would be porting.

## Measure it the long way

Isolated per-member timing at the book's shapes, the way todo-636 and todo-643 took
theirs (`.todo/123-gpu-acceleration/mtl-attention-softmax.lisp` is the closest template),
then the step from `gpt-book-shapes-fast.lisp` at two step counts, three interleaved
rounds. The wall swings ~4% on this machine, so the per-call table is what decides a small
effect. **A form that loses here stays declined and the measurement is written down.**
