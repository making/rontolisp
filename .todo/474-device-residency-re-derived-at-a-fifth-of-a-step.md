# Device residency, re-derived: the copies are now a fifth to a quarter of a `--gpu` training step

Filed 2026-08-22 off the second `--gpu --simd` profile of
`examples/llm-from-scratch/chapter03/train-gpt-soseki.lisp` at the notebook's shapes
(`.kb/gpu.md`, "The second profile, and the round it drove"). Difficulty: High.
Status: open -- measured, designed, not built.

## Why this is filed again

todo-123's phase 3 measured residency and DECLINED it: every device copy in a training
step was 1.5% of the step before the strided tier and 3.5% after it, so removing all of
them could not pay for the invalidation machinery it needs. That number is no longer
true, and it is no longer true because everything ELSE in the step got faster:

| what a `--gpu --simd` step was made of | 2026-08-21 | 2026-08-22, after todo-473 | 2026-08-22, after the second round |
|---|---|---|---|
| device copies (`memcpyHtoD` + `memcpyDtoH`) | 3.5% | 24% | **41%** of ~270 JFR samples |
| the boxed Lisp walks (Adam, RNG, where, gather, clip) | ~60% | ~40% | gone |
| per step | 0.21 s | 0.149 s | 0.119 s median, 0.097-0.144 (bimodal run to run) |

nsys over a 40-step run says what the copies are: 7060 device calls (~175 a step),
12.8 GB up in 13800 `cuMemcpyHtoD` calls (674 ms) and 8.4 GB back in 7060
`cuMemcpyDtoH` calls (402 ms), against 295 ms of kernel time. The HOST-TO-DEVICE half
-- the only half a cache can remove, because the host array must stay authoritative on
the JVM class output where an element read is a raw `daload` -- is 63% of the copy
time, so the ceiling is **roughly a quarter of a step**, not 3.5%.

## What was tried instead first, and why it did not change the route

`.todo/123-gpu-acceleration/ZeroCopyRoute.java`: the GB10 is a unified-memory machine
(`CU_DEVICE_ATTRIBUTE_PAGEABLE_MEMORY_ACCESS` = 1) and a kernel over host memory with no
copies at all is 4x the whole round trip at 1 M f32 elements -- but it is unreachable
from a movable Java heap (FFM pins a heap array for one downcall, a launch returns before
the kernel reads), and the reachable variant (Java copies into pinned buffers, the kernel
over those) loses to the driver's own pageable copy past 2^18 elements because a
single-threaded `MemorySegment.copy` runs at 35-60 GB/s against the driver's ~53. So the
copy route stays, and the only lever left on the copies is not moving them at all.

## The design (from `.kb/gpu.md`, "The residency design that was weighed")

- An IDENTITY-keyed cache over the primitive `double[]` / `float[]` -- the one mechanism
  that exists on both the interpreter (`LispDoubleFloatArray.data()`) and the JVM class
  output (the bare array with the `[rank, dim..., data...]` header) -- mapping a host
  array to a device buffer that holds a COPY of it. The host array stays authoritative;
  the cache removes the host-to-device copy when an operand was recently uploaded or
  was the RESULT of a device op (the common case in a chain: `amax` -> `sub` -> `exp` ->
  `sum` -> `div`, and every matmul whose operand is the previous layer's output).
- An invalidation rule: every in-place write to a packed float array drops the entry.
  The enumeration `.kb/gpu.md` made is now one entry longer on each backend:
  - interpreter: `Environment`'s `aset` / `row-major-aset` / `replace`, PLUS the
    in-place `--simd` kernels `%la-adam-step`, `%la-scatter-rows`, `%la-scale` and
    `%la-rng-fill` (which writes a FRESH array, so it needs no rule of its own, but
    the others do);
  - JVM class output: `_fvAset1` / `_fvAset2` / `_fvAsetN` and the `_aset*` chain, the
    `vec:` `-into` siblings, and the same three `--simd` kernels in the template;
  - the device kernels themselves never write a host array except through `download`
    into a result the caller just allocated.
- A release policy: an LRU against a byte budget read from `cuMemGetInfo` (a training
  run that never releases is an OOM), and `GpuTest`-style leak assertions.
- A way to MEASURE it before believing it: the `StridedCrossover` / `ElementwiseCrossover`
  "resident" columns (2.2-6.4x per op at f64, 15-18x at f32) are the per-op ceiling; the
  JFR share above is what decides. Re-run both on the program, not on a micro-benchmark.

## What would also become cheap with it

- An Adam step on the device: today 9.4 MB up and 7 MB back per parameter for a 0.5 ms
  CPU loop (8% of a step), not worth it; with the parameter, the moments and the
  gradient resident it is one kernel and nothing moves.
- Fused `softmax` / `log-softmax` / `layer-norm` device members: five and six round
  trips today, one with the chain resident; at this program's shapes they are a few
  percent, which is why they are not a separate item.
- The f32-array-times-double-scalar loops (`laEwFS`, 17% of a step): scalar by the
  precision contract on the CPU; on the device a widened multiply per element is free,
  and with the array resident it is a launch and nothing else.

## Acceptance

A `--gpu --simd` training step of the program above measurably faster than the second
round's 0.12 s median with the examples' expected output unchanged on all four backends, the
invalidation enumeration pinned by a test that writes through EVERY enumerated setter
after a device op and reads the right answer back, and the leak tests extended to the
cache.
