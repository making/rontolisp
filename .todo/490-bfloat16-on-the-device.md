# 490. `bfloat16` on the device

Difficulty: High

Part of `.todo/482`. Depends on `.todo/484`, `.todo/485`, `.todo/487`, and on `.todo/488`
existing first as the oracle. Supersedes the "`--gpu` declines a bf16 operand" clause in
`.todo/486`, which is the right behaviour only until this lands.

A decode-step GEMV is memory-bound on the device exactly as it is on the CPU, so halving
the weight bytes halves the time there too. The device has a second reason the CPU does
not: `CudaResidency`'s cap. `.kb/gpu.md` measured caps of 16 MB to 1 GB; `.todo/489`'s
1.1B model is 2.2 GB at bf16 and 4.4 GB at f32, so the width decides how much of a model
can stay resident at all, and a model that does not stay resident re-uploads every token.

## The ceiling, stated up front so nobody expects a discrete card

**GB10's 128 GB of LPDDR5X is shared by the CPU and the GPU at ~273 GB/s.** The 20-thread
CPU path already reached 93 GB/s of that in the spike
(`.todo/482-bfloat16-a-narrow-width-that-pays/Par.java`). So on this machine a
bandwidth-bound GEMV on the device is worth perhaps **2-3x the CPU `--parallel` path, not
the order of magnitude a discrete HBM card would give** -- and `.kb/gpu.md` already says
the same thing from the other direction ("every other kind was slower resident than copied
on unified memory", and "on unified memory an upload is a memcpy of the very bytes the CPU
kernel would have streamed").

bf16's own ~2x is **orthogonal** to that choice: it applies on both sides, for the same
reason, and neither path needs the other to collect it. That is why this item is a
follow-on and not a replacement for `.todo/488`.

## Do

1. `gemv_bf16` in `src/main/resources/am/ik/gpu/gemm.cu`, PTX regenerated and checked in
   (build-time generation, per `.kb/gpu.md`). Decode with `__bfloat162float` or the
   equivalent shift; the memory traffic is the point, the arithmetic is not.
2. A third width through the `GpuDevice` seam. There is already `supportsDouble()` so a
   `#d` operand is a decline rather than a slow path; add the bf16 counterpart. **Metal
   declines**: MSL has `bfloat`, but Apple is out of scope for this item as it is for the
   rest of `.todo/482`.
3. `LinalgGpu` / `LinalgGpuKernels`: the bf16 arm of the exhaustive switches `.todo/483`
   introduced, and the residency map must accept a `short[]` host array -- including
   `FloatArrayWriteHook` invalidation, which `.todo/484` already wires.
4. **The precision row.** `.kb/gpu.md`'s contract has one per member because the device
   evaluates at the operand width and therefore diverges from the CPU at f32; the CUDA
   GEMV accumulates in *double*. bf16 needs its own row, and it has a chance the f32 case
   never had: since bf16 -> f32 is exact, a device kernel that decodes to f32 and
   accumulates the way `.todo/488`'s CPU kernel does can be made to **agree exactly**.
   Decide whether to spend the accumulator on that, and record which, either way.
5. **Re-derive the threshold.** `Gpu.worthMatvec` uses `2^17` for CUDA. Halving the bytes
   moves the crossover; measure it rather than inheriting it, the way `.kb/gpu.md`
   re-derived Metal's.
6. **The residency cap.** 2.2 GB does not fit under any cap `.kb/gpu.md` tested. Either
   raise it, make it configurable, or make the eviction policy model-aware -- and measure
   what happens when a model exceeds it, because "re-upload every token" is the failure
   mode and it must be legible rather than merely slow.

## Verify

- Device bf16 GEMV against `.todo/488`'s CPU fused kernel, on the same inputs, with the
  divergence measured and recorded in `.kb/gpu.md`'s precision table -- exact if step 4
  chose exactness, bounded and stated otherwise.
- The re-derived threshold, with the table that produced it.
- A resident 2.2 GB model: tokens per second with the cap above the model, and with the
  cap below it, so the cliff is documented.
- `--gpu --simd` and `--gpu --parallel` over bf16 agree with `--simd` alone to whatever
  step 4 committed to.
- `GpuTest` / the interceptor suites gain the bf16 claims; note `.todo/481` (the drift
  bound is flaky under the parallel suite) before adding a drift assertion.
