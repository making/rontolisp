# A `--gpu` result comes home after every call, and that round trip is now the step

Difficulty: High

Filed 2026-08-22 off the fourth profile of
`examples/llm-from-scratch/chapter03/train-gpt-soseki.lisp` at the notebook's shapes
(`*n-embd*` 384, `*block-size*` 256), JVM class output, `--gpu --simd`, 200 steps on the GB10
-- the profile that closed the register-tiled GEMM (`.kb/gpu.md`, "The register-tiled f32
GEMM") and found that the kernel was not the first line. Read `.kb/gpu.md` "Device
residency, built" first: it is the design this item would replace, and it says in its own
last row why ("what it does not yet buy is the steady state").

## What the profile says

Steady-state step (steps 40-200) about 0.06 s. `nsys profile -t cuda` over the run:

| | per 200-step run |
|---|---|
| kernels, all | 1.63 s (`gemm_batched_f32` 1.05 before the tiled kernel, `fold` 0.29, `bcast` 0.15) |
| `cuMemcpyDtoH`, GPU time / API wall | 0.79 s / 2.04 s -- 37534 copies, **44 GB** |
| `cuMemcpyHtoD`, GPU time / API wall | 0.67 s / 0.86 s -- 36723 copies, 21.7 GB |
| `cuCtxSynchronize` | 0.70 s |

JFR (`jdk.ExecutionSample` AND `jdk.NativeMethodSample` -- the pinned download is a
non-critical downcall and only shows in the second): of ~1000 weighted samples, the
download wait 17%, `memcpyHtoD` 10%, the bounce-buffer `Unsafe.copyMemory0` 9%, the kernel
wait 5%; `laEwFS` (f32 array against a double scalar) 12%, `laAdamStep` 7%, the CPU lane and
select kernels ~25%, fresh-array allocation (`newLike` / `laNewLikeF`) 6%. **Every device
result is downloaded -- 220 MB per step -- and about 40% of the step is that traffic and
the waits around it.** The copies are no longer hidden behind anything: the GEMM is now
2-4x faster at its big shapes and the step barely moved, because the product's download
(6 MB for a (4 256 1536) activation) costs more than the product.

Two things were tried and rejected on the way, so they need not be tried again:
- `laEwFS` is already auto-vectorized by C2 (0.28 ns/element at 1.5 M; the Vector API's
  `F2D` conversion is 100x slower, not intrinsified on aarch64). Its division half
  (`torch:div` by `sqrt d-k` and by `sqrt 2` in `gelu`, the dropout scale) is 2x the
  multiply; `x * (1/s)` measured bit-identical on 1.5 M samples but is not so by
  construction, so it stays a division.
- array-op-scalar as a device member: the current kernel takes both operands from device
  memory at the array's width, so a double scalar would need a kernel of its own; the gain
  under the present design is ~4% of the step (the CPU loop is 0.3-0.8 ms per 1.5 M, the
  device ~0.45 including the download). Not worth a tier NOW; under the design below it
  is free.

## The design this asks for

The host array stops being the source of truth while a chain runs: a device member's
RESULT stays on the device and comes home only when the host first reads it -- "download
on first host touch" -- and a host array that is already resident and unwritten is never
uploaded (that half exists). Then a chain `matmul -> div -> where -> softmax -> matmul`
downloads nothing until something reads it, and the members that were REFUSED because a
round trip cannot beat a lane loop (equal-shape `add`/`sub`/`mul`/`div`, the scalar forms,
`where`, `sqrt`, the Adam update over resident parameters) become launches with no copy,
which is where the other 25% of the step is. The ceiling is therefore not the 40% alone.

What it costs, and why it is High: **every host read of packed-array storage needs a seam**
that materializes the array first. Enumerate them as the writers were enumerated for
todo-474 (`.kb/gpu.md`, "The invalidation, as built"):
- the JVM class output: `_fvAref1/2/N` and `_fvDims` (header only -- dims are written at
  allocation and never stale, so `_fvDims` needs NO seam), every `RontoLispSimdBridge`
  entry that takes a `float[]`/`double[]` (one call at the top of each), the typed loops of
  `JvmTypedLoopCompiler` (raw `faload` -- materialize the loop's arrays at loop entry;
  they are loop-invariant), `_readSeqPacked`'s sibling `_writeSeqPacked`, the printer, and
  `aref` through the general `_aref*` chain;
- the interpreter: `LispSingleFloatArray.data()` / `LispDoubleFloatArray.data()` is read at
  dozens of sites; a `materialize()` on the record read path, or an eager mode (the
  interpreter gains nothing from `--gpu` anyway -- 26 s a step against 0.06 compiled -- so
  it could simply keep downloading and the JVM bridge alone switch to lazy).
- a read that misses the seam reads STALE bytes silently -- so the pin is a test that
  enumerates the readers the way `everyEnumeratedWriterInvalidatesTheResidentCopy` does
  the writers, on both backends, plus a `-Pweb` build.

And the budget: a lazy result holds device memory until read or collected; with weak keys
the collector decides, and the cap (`RESIDENT_CAP`) must then evict by DOWNLOADING rather
than dropping. `GpuDevice` grows one method (`materialize`) that Metal implements the same
way or declines (on unified memory a Metal result is already a host pointer -- measure
whether the copy was ever the cost there).

## Acceptance

The 200-step run's `cuMemcpyDtoH` count and bytes down by an order of magnitude; the
step's ratio against `--simd` past 10x (today 7x at steps 5-40, and the steady-state
steps are where the copies dominate); every `GpuTest` / interceptor test still passing,
including the writer enumeration; a reader enumeration of its own; and the numbers in
`examples/llm-from-scratch/README.md` and `doc/{en,ja}/guides/gpu-acceleration.md`
re-measured, not edited.

## Also measured, for the README (2026-08-22)

`--blas` changes nothing on this program: every product is the stacked rank-3 one, which
`--blas` does not take. `--simd --parallel` halves the CPU step (0.79 -> 0.37 s) and
`--gpu --simd --parallel` is within noise of `--gpu --simd` (0.109 vs 0.115). On the JVM,
`-XX:+UseParallelGC -Xmn4g` takes the 40-step `--gpu --simd` run from 6.0 to 5.0 s: the
step allocates a fresh 6 MB array per activation and G1 pays for it (the `div+fresh-alloc`
row of the `laEwFS` probe: 0.75 ms of arithmetic, 1.1-2.6 ms with the allocation).

## The same step at the BOOK's shapes (2026-08-23)

Run once more with the corpus the notebook actually trains on (the whole of
『吾輩は猫である』, 318 k characters, 3038 distinct, fetched and stripped beforehand) and
the notebook's own configuration -- `block-size` 256, `n-embd` 384, 6 layers, 6 heads,
batch 64 (13.06 M parameters) -- `--gpu --simd`, JVM class output,
`-XX:+UseParallelGC -Xmn8g -Xmx64g`: **9.9 s a step** (`(t13 - t3) / 10`), so the
notebook's 5000 steps would be about 14 hours here. The arithmetic is ~1.2 TFLOP a step;
at the tiled kernel's measured rate that is 0.2 s. Where the other 9.7 go (JFR
`ExecutionSample` + 2x `NativeMethodSample`, ~6400 weighted samples over 13 steps):

| | share |
|---|---|
| the bounce-buffer memcpy (`Unsafe.copyMemory0`) + the pinned DtoH wait + `ctxSynchronize` + `memcpyHtoD` | ~40% |
| `laWhere` (the causal `masked-fill`, 6 heads x 6 layers, forward and backward) | 10% |
| `laEwFS` (f32 array against a double scalar) | 13% |
| the lane kernels (`laEwFF`, `laBcastFF`, the Vector API load / op / store frames) | 14% |
| `laNewLikeF` + 20.5 s of ParallelGC pauses in a 145 s run | ~18% |

`nsys` over the 3-step run: 88 GB downloaded and 40 GB uploaded (sampling 400 tokens
without a KV cache is 71 k launches of the small `gemm_f32` on its own), DtoH API wall
3.8 s against 1.9 s of kernel time for the whole run. So at the shape the README calls the
book's, the device is idle more than nine tenths of the time, and three of the four lines
above are the SAME finding: every result of a member is copied home, every non-member
(`where`, the scalar forms, the equal-shape ops) then runs on the host over that copy,
and every one of them allocates a fresh 100 MB array. The design above removes the first,
turns the second into launches, and leaves the third -- which says the host array itself
should be allocated lazily for a result that never comes home, and that is the one
representation question the JVM backend's `[rank, dim..., data...]` header makes hard.
