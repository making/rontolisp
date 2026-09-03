# 488. The fused `bfloat16` GEMV/dot kernels -- where the 1.6x comes from

Difficulty: Medium

Part of `.todo/482`. Depends on `.todo/484` and `.todo/485`.

This is the item that makes `bfloat16` a *performance* width and not merely a smaller one.
Measured (`.todo/482-bfloat16-a-narrow-width-that-pays/Worth.java`), a GEMV over bf16
weights and f32 activations, 4 accumulators + FMA:

| shape | f32 weights | bf16 weights | ratio |
| --- | --- | --- | --- |
| 1024x1024 (4 MB, cache-resident) | 0.07 ms | 0.08 ms | 0.88x |
| 4096x4096 (67 MB, DRAM-bound) | 1.91 ms | **1.25 ms** | **1.60x** |

The win is bandwidth: half the weight bytes, and a decode cheap enough not to eat it.
It only appears once the matrix leaves cache, which is exactly the regime an LLM's weights
live in and the reason `.todo/489` is the goal.

## Why this is safe, unlike the f16 version it replaces

An earlier draft of `.todo/482` forbade narrow kernels outright, because IEEE f16 both lost
on speed and raised a bit-identity question. Neither applies here:

**bf16 -> f32 is exact.** The widening is `bits << 16`; a bf16 value *is* an f32 value. So
a fused kernel that decodes lane-by-lane and accumulates in f32 produces bit-for-bit the
same result as widening the whole matrix into an f32 array and running the existing f32
kernel over it, provided the accumulation order matches. There is no precision decision to
make and no new entry in the cross-backend identity contract -- only the existing rule that
`FSPECIES_REDUCE` stays `SPECIES_128` so the answer does not depend on the host's vector
width (`.kb/vec.md`, "The lane-count pin").

That equivalence is the item's main test: **fused == widen-then-f32-kernel, bit-for-bit**,
which also gives the scalar `vec.lisp` defun a cheap oracle.

## The second JIT, and the rule it imposes (2026-09-03)

Every number above was taken under Graal. Re-run under C2 (`-XX:-UseJVMCICompiler`,
what a stock OpenJDK runs a compiled `.class` under), the spike's own `Worth.java` kernel
-- both decoders in one method behind a boolean -- fell to **0.20x** of f32 at 4096x4096:
the method overran C2's inlining budget for the Vector API chain and every vector was
boxed. The identical bf16 decode in a method of its own runs at **2.06x** under C2 and
1.48x under Graal (`Jit.java`, round 2 of the spike record). So:

- **One small kernel method per width.** No decoder shared behind a flag, no width
  switch inside the lane loop; the bf16 arm of `matvecRowsF` is its own method with its
  own loop, mirrored in both kernel files as usual.
- **Every kernel number is taken under both JITs**, Graal (CI, the native image, this
  box's default) and C2, and recorded with the JIT named. A shape that is fast under one
  and boxed under the other is not done.
- The decode shape: `ShortVector.fromArray(S_64) -> convertShape(S2I, S_128, 0) -> LSHL 16
  -> reinterpretAsFloats` (2.06x C2 / 1.48x Graal), or an `S_128` short load split with
  parts 0 and 1 (1.60x / 1.56x). `convert()` is not the widening op -- it preserves the
  vector SHAPE and yields a 2-lane int vector -- `convertShape` is. Plain scalar loops
  (0.34-0.44x) and a widen-into-L1-scratch-then-f32-kernel (0.58-0.75x) both lose on
  both JITs; they were measured so nobody proposes them as the simpler route.

Under 20 threads (`Quant.java par`, 8192x8192, DRAM-resident) bf16 is 1.63x under Graal
and 1.70x under C2, at 74-80 GB/s against f32's 91-95: the parallel arm inherits the
serial kernel unchanged and stays bandwidth-bound.

## Do

1. `eval/VecSimdKernels` + `codegen/jvm/JvmSimdVectorTemplate` -- the two must mirror each
   other operation for operation, as `.kb/vec.md` requires. The decode is one line:
   ```java
   FloatVector dec(short[] w, int off) {
       return ((IntVector) ShortVector.fromArray(SSH, w, off).convertShape(S2I, IS, 0))
               .lanewise(LSHL, 16).reinterpretAsFloats();
   }
   ```
   with `SSH` a short species of the same lane count as the int species, exactly as the
   probe does.
2. Fuse it into `matvecF` / `matvecIntoF` / `dotF` / `sumF` where the operand is bf16.
   Reductions accumulate in **f32**, matching the f32 kernels, and promote once at the
   value boundary.
3. `.todo/480` proposes multiple accumulators per row for the f32 kernels; the probe here
   already uses four, and the bf16 numbers above assume them. Land whichever order is
   convenient, but do not measure bf16 against a single-accumulator f32 baseline -- that
   flatters bf16 and the comparison above would not reproduce.
4. Element-wise `vec:` kernels over bf16: widen, compute in f32, narrow on store. Do not
   try to keep intermediates at bf16 -- 8 mantissa bits compounds fast, and the width is
   for storage, not for intermediate arithmetic.
5. `--parallel` (`.kb/simd-parallel.md`): `matvec`/`matvec-into` split by row range and
   stay bit-identical to the serial kernel; the bf16 arm inherits that unchanged.

## Verify

- **Fused == widen-then-f32-kernel, bit-for-bit**, on random matrices at several shapes
  and ranks, on the interpreter and on a compiled `.class`, serial and `--parallel`.
- Interpreter `--simd` and compiled `--simd` agree bit-for-bit, as the two kernel files
  already must.
- Re-measure the table above on the implementation. If the 4096x4096 ratio is not clearly
  above 1.0, something is not vectorizing -- check that `convertShape` intrinsified rather
  than assuming.
- Measure on x64 as well as aarch64 (the spike was aarch64 only). A left shift is a left
  shift, so the shape of the result should hold, but the crossover size will move with the
  cache hierarchy.
- Measure under both JITs (above) -- Graal and `-XX:-UseJVMCICompiler` -- and a `.class`
  run under a stock OpenJDK if one is at hand. The 0.20x cliff is silent: no warning, no
  exception, the same bits, five times slower.
- The cache-resident case (0.88x) is a real regression against f32 for small matrices.
  Decide whether a size threshold is wanted -- `.kb/vec.md` already has a `THRESHOLD = 128`
  precedent -- or whether the memory saving justifies it unconditionally, and record which.
