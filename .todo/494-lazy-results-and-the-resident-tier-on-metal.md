# Lazy results and the resident tier on Metal (the Apple half of `.todo/491`)

Difficulty: High

Filed 2026-08-23 when `.todo/491` closed. That round was built and measured on CUDA
(GB10) only: a device member's result stays on the device until the host reads it, every
host reader on both interceptors calls `Gpu.materialize`, and the members that a round
trip had refused (`zip`, `scale`, the comparison masks, `sqrt`/`abs`/`negative`/`sign`,
`where`, the Adam step, the strided `copy` behind reshape / rank-2 transpose / slice /
concatenate / `%la-scale`) run over a RESIDENT operand. `MetalGemm` declines all of it:
`lazyResults` is ignored, `materialize` is a no-op, every resident-tier member answers
`false`, and `gemm.metal` has no entry point for them (`.kb/gpu.md`, "A result comes home
on first host touch"; `MetalGemm.lazyResults`'s javadoc). `.todo/492` and `.todo/493`
are the CUDA half's follow-ups and do not apply here until this lands.

Needs Apple hardware to run and to measure; the design is portable, the decision is not.

## Why it is a measurement first, not a port

`.kb/gpu.md` "Residency and the GEMV on this backend" (todo-477, M4 Max) measured
keeping every operand and result resident at 1-5% SLOWER than the pure pool, at every
cap, and kept residency for the GEMV matrix alone. Two things in that measurement were
specific to EAGER results and are what `.todo/491` changed:

- every result still came home before the call returned (a memcpy from the slab's
  `contents`, ~75 us per 1.5 MB), so residency saved only the upload, which on unified
  memory is the same memcpy in the other direction;
- a resident copy held a slab out of the pool, and a fresh slab pays first-touch page
  faults (~1 us a page) -- the cost that made the pool mandatory.

Lazy results remove the first cost entirely (the result is never copied in either
direction while it stays on the device, and the resident tier runs the next member over
it), and change the second: the slab a result occupies is the slab the next member reads
from, not a slab held idle. Whether that turns the 1-5% loss into a win at a training
step's shapes is the open question. The acceptance rule is todo-477's: if the step is not
faster than the pure pool, record the number in `.kb/gpu.md` and keep the decline.

## Do

1. `gemm.metal`: `zip_f32`, `scal_f32`, `where_f32`, `adam_f32`, `copy_f32`, and cases
   12-15 in `map_f32` (`sqrt` with the canonical NaN, `abs`, `negative`, `sign`), each
   bit-identical to the CPU kernel in `LinalgSimd` the way the strided tier already is
   (`theStridedTierIsBitIdenticalToTheScalarOracle`; `#pragma METAL fp contract(off)`,
   no fast math, no FMA, the CUDA `gemm.cu` is the reference for the formulas -- `adam`'s
   in particular). The MSL is compiled at run time and travels verbatim, so nothing to
   regenerate. Float only: `#d` stays a hard decline (MSL has no `double`).
2. `MetalGemm`: honour `lazyResults`; a member's result slab goes into the shared
   `DeviceResidency` as DIRTY (`put(..., dirty)` / `markDirty`) instead of being
   downloaded and recycled; `materialize` downloads and marks clean; the drain's flushes
   (`DeviceResidency.Flush`) DOWNLOAD before they give the slab back to the pool (today
   the drain only releases, which is right only while nothing is dirty); `written` =
   materialize + drop, as on CUDA. `MetalGemm.mapF` drops its `op >= MAP_LIBM_OPS`
   decline; the resident-tier members mirror `CudaGemm`'s (`launchFlat`, the `where`
   placeholder, `adamStep`'s 16-slot block, `copy`'s spans and walk origins).
3. The budget. CUDA's lazy rule ("everything less an eighth, floor 512 MB") was derived
   from `cuMemGetInfo`; Metal's residency budget is a fraction of the POOL
   (`derivedResidentBudget`, `recommendedMaxWorkingSetSize` less `currentAllocatedSize`)
   and the pool and the resident set compete for the same slabs. A lazy budget too small
   flushes live activations (the trap `.todo/491` hit at 1 GB on CUDA); too large starves
   the pool and every call pays a fresh slab. Measure at the notebook's shapes AND the
   book's (`examples/llm-from-scratch/README.md` has both) before fixing the rule.
4. Nothing in the interceptors: `LinalgGpu.hooks()` and `JvmGpuTemplate.gpuKernels`
   already switch `lazyResults(true)` on and materialize at every enumerated reader; the
   `Gpu` policy already offers the tier over `resident(...)`. `JvmGpuRuntimeBuilder` needs
   no new class unless `MetalGemm` grows a nested one (`GPU_CLASSES`).
5. Tests: `MetalGpuTest` counterparts of `GpuTest`'s
   `aLazyResultStaysOnTheDeviceUntilTheHostFirstReadsIt`,
   `aWriteToALazyResultBringsItHomeFirst`,
   `anEvictedOrReleasedLazyResultIsDownloadedNotDropped`,
   `theResidentTierIsOfferedOnlyOverAResidentOperandAndLandsOnTheCpuKernelsBits`,
   `theStridedCopyIsTheCopyMembersOverAResidentOperandAndAScaleRunsInPlace`;
   `MetalGpuTest.theAxisFoldIsDeclinedAtEveryWidthAndSize` and
   `onlyTheMatrixOfAnAcceptedGemvIsKeptResident` are rewritten or retired by the
   measurement's outcome, not silently; `GpuDeclineTest`'s mirror checks gain the new
   `gemm.metal` entry points; `MetalGpuTest` drops `sqrt`-declines.
6. Measure `train-gpt-soseki` on the JVM class output, `--gpu --simd`, `(t40 - t5) / 35`,
   interleaved rounds, against the pure pool (todo-477's 0.104 s/step on an M4 Max), and
   record the table in `.kb/gpu.md`'s Metal section, the Mac line of
   `doc/{en,ja}/guides/gpu-acceleration.md`, and the example README -- whichever way it
   goes.

## Out of scope

`.todo/492` (no host allocation for a lazy result) and `.todo/493` (the remaining host
reads) -- they apply to this backend only once lazy results are in, and each is its own
measurement here too.
