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
- The cache-resident case (0.88x) is a real regression against f32 for small matrices.
  Decide whether a size threshold is wanted -- `.kb/vec.md` already has a `THRESHOLD = 128`
  precedent -- or whether the memory saving justifies it unconditionally, and record which.
