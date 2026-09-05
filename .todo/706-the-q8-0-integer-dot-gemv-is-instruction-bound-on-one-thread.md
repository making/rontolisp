# 706. The Q8_0 integer-dot GEMV is instruction-bound on one thread: 0.7x of f32 under C2

Difficulty: High

Left open by `.todo/672` (closed 2026-09-05). Measured on the shipped kernel
(`VecSimdKernels.matvecQ8F` / `JvmSimdVectorTemplate.matvecQ8F`, GB10, both JITs, the
numbers and conditions in `.todo/672-a-q8-0-quantized-weight-matrix-and-its-integer-dot-gemv/README.md`):

| 4096x4096, one thread | Graal | C2 |
| --- | --- | --- |
| Q8_0 integer dot, ratio to the shipped f32 GEMV | 1.42-1.52x | **0.70-0.77x** |
| fused bf16, same baseline | 1.31-1.41x | 1.81-1.98x |
| Q8_0 under `--parallel`, 20 threads | 2.6-3.3x | 1.4-1.8x |

A compiled `.class` run under a stock OpenJDK (C2) with `--simd` therefore runs a Q8_0
model's GEMVs SLOWER than the same model's f32 GEMVs on one thread, while moving a quarter
of the bytes. The kernel is instruction-bound: 12 Gelem/s under Graal is 13 GB/s against
a ~27 GB/s single-thread ceiling, 6 Gelem/s under C2 half that, and the `--parallel`
column reaches 105-140 Gelem/s, so the bandwidth is not the limit.

## Why

The Vector API has no int8 dot-product instruction. One 32-element block is two 16-byte
loads a side, eight `convertShape(B2S)`, four short multiplies, two short adds, four
`convertShape(S2I)`, three int adds, one `convert(I2F)` and one f32 multiply-add -- about
30 instructions where ggml's NEON kernel spends two `SDOT` (`vdotq_s32`, 16 int8
multiply-accumulates each) plus a scale. C2 compiles that chain roughly half as well as
Graal does (the two probes in the README's shape table: 5-6 Gelem/s at every shape, the
same rate as the rejected reduce-per-block shape, which says the widening chain and not
the fold is what C2 is slow at).

## Options

- **Find what C2 does with the `B2S`/`S2I` chain.** `-XX:+PrintCompilation` and
  `-XX:+PrintInlining` over `Q8GemvBench` under `-XX:-UseJVMCICompiler`; the bf16 decode
  (`convertShape(S2I)` from a 64-bit species) is fast under C2, so a 64-bit
  `ByteVector.SPECIES_64` load with a single expanding `B2S` per half-block may be the
  shape C2 likes. Any change must keep the four lane sums' definition (lane `i` = columns
  `j mod 4 = i`) or change the defun with it (`.kb/quantized-matrix.md`).
- **A wider block product.** Multiply as shorts but accumulate two blocks' products in
  one short vector before widening -- not possible: `|4 x 128 x 127|` overflows 16 bits,
  and the scales differ per block anyway.
- **Accept it and say so**: Graal is what this box, CI and the native image run, and
  `--parallel` wins under both JITs. If that is the answer, the docs should say which JIT
  the one-thread win needs.
- The real fix is an int8 dot-product instruction the JDK does not expose; watch
  `jdk.incubator.vector` for a `VectorOperators` dot-product or a `SDOT`-shaped lanewise
  op.

Whatever lands, `Q8GemvBench` / `Q8TemplateGemvBench` are the harness, and the bit
identity with the defun (`VecSimdQ8KernelsTest`, `JvmSimdVectorTemplateQ8Test`,
`QuantizedMatrixTest`, `JvmQuantizedMatrixTest`) must hold before any number is read.
