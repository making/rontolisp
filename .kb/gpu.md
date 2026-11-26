# `--gpu`: a matrix product on the GPU, or a decline

Two layers. **`am.ik.gpu`** is a language-independent library that takes a member and either runs it
on a GPU or answers "no" -- CUDA through the driver API, Metal through Objective-C, behind one sealed
`GpuDevice` seam. **The interception layer** is the `--gpu` flag over it, on the interpreter and the
JVM class output; a `.wasm` output refuses the flag outright and always will.

Posture copied from `--blas` (`.kb/linalg-blas.md`; declined-input protocol in `.kb/linalg-simd.md`):
**recommended, never required; a machine without the hardware runs the same programs to the same
output.** User-facing description: `doc/{en,ja}/guides/gpu-acceleration.md`. The numbers here
came from standalone probes (removed from the tree on 2026-11-26, kept in the project's
private history) plus the `*-baseline.lisp` CPU baselines. Calibration machines: **NVIDIA GB10** (`sm_121`,
48 SMs, unified addressing, CUDA 13, aarch64) and **Apple M4 Max** (40 GPU cores, unified memory),
both on Oracle GraalVM 25. A different device changes every number; the SHAPE of each result should
survive.

## The invariant

**`am.ik.gpu` never throws and never signals.** Every failure -- no driver, no device, an old card, a
shape it cannot launch, a member too small, device memory exhausted, any `CUresult`, a command buffer
that did not complete, an unsupported width, a JVM that forbids native access, neither
`libcuda.so.1` nor `Metal.framework` -- is `null` or `false`, and the caller runs what it would have
run anyway.

**Three deliberate non-declines.** A `null` operand throws `NullPointerException` (`@NullMarked`).
The array-returning `multiply` overloads can throw `OutOfMemoryError`; the `out`-taking overloads an
interceptor calls allocate nothing. **`Gpu.materialize` cannot decline**: when the host has no other
copy of the bytes, a refused download is an `IllegalStateException`.

**Package rule**: no rontolisp package, no external dependency. `eval -> am.ik.gpu`,
`codegen.jvm -> am.ik.gpu`, `am.ik.gpu -> nothing`.

Classes: `am.ik.gpu.Gpu` (the whole public surface), `GpuDevice` (the sealed seam), `CudaGemm` /
`CudaDriver` / `CuResult`, `MetalGemm` / `MetalDriver`, `DeviceResidency`;
`am.ik.rontolisp.FloatArrayAccessHook` (the interpreter's two seams); `eval.LinalgGpu` /
`LinalgGpuKernels` (the latter the ONE reference to `am.ik.gpu` from `eval`, so `-Pweb` can cut it);
`codegen.jvm.JvmGpuTemplate` / `JvmGpuRuntimeBuilder` / `JvmLinalgGpu`; kernels
`src/main/resources/am/ik/gpu/gemm.cu` / `gemm.ptx` / `gemm.metal`.

## The API

Row-major `n x m` by `m x p`. Load-bearing properties:

- **`worth` and the member re-ask the same question**: `worth` so a caller can refuse before it
  unwraps operands, the member so the check cannot be bypassed. **Every `worth*` is probe-free and
  answers with the POOLED CUDA constant on every machine**; the cost is a band below a backend's
  higher threshold where an interceptor derives strides and the library declines anyway. A test pins
  100k `worth` calls under 200 ms with no probe run.
- **The batched product is one call plus a per-batch ELEMENT STRIDE per operand** -- one launch for
  the stack. Stride 0 is a BROADCAST operand (one slab copied); a launch reads
  `(batch - 1) * stride + n * m`.
- **A member is a PARAMETER, not an entry point**: `map` switches on sixteen `MAP_*` codes,
  `bcast`/`zip`/`scale` on eleven `BIN_*` codes. An unnamed op code is a decline, and each kernel's
  `default` is the identity, so a slipped mirror cannot silently answer another function.
  `GpuDeclineTest` checks the mirrors against both kernel texts.
- **The offsets are mandatory.** The compiled backend keeps a `[rank, dim..., data...]` header inside
  the same array; the interpreter passes 0. The result carries no header.

## The runtime requirement is the driver, and nothing else

`SymbolLookup.libraryLookup("libcuda.so.1", ...)` plus a `downcallHandle` per entry point. No JNI, no
bundled shim, **no CUDA toolkit** (`libcuda.so.1` ships with the driver, which keeps the
no-external-dependencies rule). Run-time NVRTC compilation must not return.

On Apple: `libobjc`, `Metal.framework`, `MetalPerformanceShaders.framework` and the MSL compiler are
the OS; no Xcode. `objc_msgSend` must be CALLED through a prototype matching the selector on arm64,
not as the variadic it is declared as -- **`MetalDriver` holds one handle per selector SHAPE**. A
selector taking an `MTLSize` by value through a `long` shape is an immediate SIGBUS.

## The kernels: PTX checked in, MSL compiled at run time

`gemm.ptx` is what `nvcc` makes of `gemm.cu`; the driver JIT-compiles it for the card present.
Regenerate the pair together (a DEVELOPER-only toolkit requirement):

```bash
nvcc -arch=compute_75 -fmad=false -ptx src/main/resources/am/ik/gpu/gemm.cu -o /tmp/gemm.ptx
sed -n '1,12p' src/main/resources/am/ik/gpu/gemm.cu > src/main/resources/am/ik/gpu/gemm.ptx
cat /tmp/gemm.ptx >> src/main/resources/am/ik/gpu/gemm.ptx
```

The first twelve lines of `gemm.cu` (`//` comments, valid PTX) carry the regeneration command onto
the artifact; `GpuDeclineTest` asserts it is there.

- **`compute_75` is the floor because CUDA 13 refuses anything older**; an older card declines at the
  probe with that reason.
- **No module cache plumbing**: the driver's `~/.nv/ComputeCache` makes a load 26 ms cold, 1.4 ms
  after. MSL: ~35 ms cold, 2-3 ms warm.
- **`MTLMathModeSafe` is set explicitly** (falling back to `setFastMathEnabled:NO`): the relaxed
  default flushes denormals and reassociates, and the strided tier claims BIT-IDENTITY.
- **`Gpu.useKernels` / `useMetalKernels` supply the text for an embedder carrying the CLASSES but not
  the resources** (the JVM backend, the only caller). A call after the probe changes nothing.

## The probe, and lifetimes

One probe per process, cached in `Gpu`'s static initializer, never throwing; CUDA first, Metal
second. Compute capability `>= 7.5` is checked explicitly so the reason is legible.

- **Only the stream-ordered allocator is an OPTIONAL symbol**. `CudaDriver.open` / `MetalDriver.open`
  answer `null` only when the LIBRARY is absent and let a binding failure THROW, so the probe says "the
  driver could not be bound" -- a missing native-image registration fails at BINDING time.
- **Primary context + module retained for the process**; a partial probe failure unwinds
  (`CudaGemm.unwind`); per-call device buffers are freed on every path.
- **The five `...FreesEveryBufferItAllocates` leak tests measure the POOL, not the device**
  (`CudaGemm.poolBytesInUse`, `GpuTest.driftSample`): `cuMemGetInfo` reports the whole device and
  drifts 1.78-1.85 GB against the old 1.5 GB `GpuTest.DRIFT_BOUND`, close enough to real-leak sizes
  that widening would hide leaks. `GpuTest.driftBound` falls back to `cuMemGetInfo` only with no pool.
  On Metal: `MetalGpuTest.aRunOfCallsSettlesTheBufferPoolRatherThanGrowingIt`.
- **Threads.** Concurrent members are correct without a lock. `DeviceResidency` is NOT thread-safe
  and the device attempt runs on the calling thread, hence `--parallel` strictly below the device
  decision. Open caveat: a copy issued while another thread's kernel is queued on the null stream
  waits for it inside the critical window; per-thread streams are the fix.

### A DECLINE MUST COST THE DEVICE NOTHING, and that takes three calls in order

A pooled allocation that FAILS still grows the pool on the way, and the high-water mark survives for
the process and against every other CUDA process (one declined 80 GB product took a 128 GB device
from 69 GB free to 1 GB, permanently).

1. **A pre-flight**: the buffers' total against `cuMemGetInfo` less 64 MB headroom. It costs 6-13 us,
   so it is AMORTIZED (re-asked every 64 allocations or when a request exceeds a quarter of the
   remembered figure, `CudaGemm.allocate`) and errs towards REFUSING.
2. **A trim after a failed allocation -- in THIS ORDER, or it silently does nothing**: `release()` the
   buffers that did allocate; `cuCtxSynchronize` (`cuMemFreeAsync` only QUEUES); `cuMemPoolTrimTo`.

Twelve declined 80 GB products then move free memory by 0 MB.
`GpuTest.aDeclinedProductCostsTheDeviceNothing` asserts the MEMORY, not the return value.

## `Linker.Option.critical` takes heap segments here too -- with a different bound

`cuMemcpyHtoD` / `cuMemcpyDtoH` take `MemorySegment.ofArray(a).asSlice(...)` under `critical(true)`.
Staging in a confined arena loses at every size (1.04x at n=8 to 3.09x at n=1024), so **the UPLOAD is
never staged**; the download is, for the fresh-page reason under residency.

A critical call cannot safepoint. Two rules:

1. **A copy over `CRITICAL_CHUNK_BYTES` (64 MB, ~1.1 ms) is SPLIT into chunks.**
2. **A device-to-host copy on the null stream also WAITS for the kernel** (36 ms at n=2048 f64 inside
   the critical window against 548 us after an explicit wait). Chunking cannot help, so the kernel is
   awaited by a plain `cuCtxSynchronize` first. **The threshold is per-device**:
   `SYNC_FLOPS_PER_MULTIPROCESSOR = 1 << 22` times the SM count (2^28 on 48 SMs, ~0.6 ms). The
   comparison is `>=`: n=m=p=512 at f64 lands exactly on 2^28 and must sync.

Every critical path drains the launch queue first through `awaitQueued`, so the window holds the copy
alone -- which lets lazy results skip the post-launch wait (`CudaGemm.awaitLaunched`). The `queued`
flag is volatile and racy by design: a race costs one extra or late synchronize, never a wrong answer.
Metal stages through `MemorySegment.copy` into a shared slab: no critical window.

## Declining on error, and the sticky rule

`CuResult` is the full CUDA 13 table -- **101 statuses, diffed against `cuda.h` at
`CUDA_VERSION 13000`** -- with one derived property, `sticky()`. **Re-diff against the header when
extending it.** An invented constant is worse than a missing one: a wrong `sticky` flag can leave a
dead context paying full round trips.

- **Any non-zero status declines**, after freeing every buffer; `CUDA_ERROR_OUT_OF_MEMORY` is ordinary.
- **A sticky status retires the feature for the process** (seventeen marked: launch failures,
  uncorrectable memory errors, a destroyed context, a driver mismatch); `CudaGemm` sets `usable = false`.
- **An unrecognised code** -- `CuResult.of` answers `null`, `isSticky` assumes the dangerous kind.
- **Metal has no such state**: a command buffer not `Completed` is a per-call decline.

## Every threshold, and what fixed it

A threshold sits where the win is UNAMBIGUOUS, not where it first appears: a "win" that is really a
tie is the one way this flag can do harm. Each was measured against the fastest CPU path (`--simd`,
JIT-warm) and re-derived per backend.

| member | CUDA | Metal | what fixed it |
|---|---|---|---|
| product `n*m*p` | **2^17** (2^21 unpooled) | **2^22** | crossover n≈45 f64, n≈51 f32 on the GB10; on the M4 Max n=128 a tie, n=192 4.4x |
| STACKED product `batch*n*m*p` | 2^17 | 2^22 | the floor is paid once for the stack |
| element-wise map, elements | **2^14** (2^16 unpooled) | **2^17** | every member taken clearly ahead, every refused clearly behind |
| broadcast / axes transpose, OUTPUT elements (`MIN_STRIDED_ELEMENTS`) | **2^15** (2^17 unpooled) | **2^18** | 1.2x at 16384 is inside the noise; 2.1x at 32768 is not |
| axis fold, INPUT elements | **2^17**, and at least **256** output cells | **declined at every size** as a round trip | one output cell is a single-threaded device loop |
| axis fold over a RESIDENT operand | 32 cells | `MIN_RESIDENT_ELEMENTS` | the CPU alternative is a DOWNLOAD |
| GEMV (`vec:matvec`), `rows*cols` | **2^17** (2^20 unpooled), `#f` and `#bf16` | **2^21** | plus the two-sight rule. f32 is a tie at exactly 2^17 and 1.5x from ~768x288; kept because 2^18 would drop llama2's 768x288 matrices |
| generator fill, elements | **2^13** | declined -- needs a `double` | 0.7-0.8x at 2^12, 1.6-1.8x at 2^13 |
| the RESIDENT tier | any size | **2^14** | a launch with no copy |
| MPS instead of our kernel (`MPS_MIN_WORK`) | -- | 2^27 per matrix | MPS carries ~35 us of object churn and loses below n≈448 |

**The element-wise rule: a member is worth a round trip when its scalar cost is a libm CALL, not a
machine instruction.** At 1.5 M f64 elements `erf` 124x, `tanh` 22.6x, `exp` 17x, `log` 13x, `sin`
9.3x -- `sqrt` 1.4x, binary `add`/`mul` 1.15x; at f32 the binary ops LOSE. **A member that wins by
less than its own measurement error is not a member.** Two traps in any CPU column: a CPU figure
depends on which widths the PROCESS has run (1.3-1.9x), and the first shape measured in a process pays
~500 us a call for its first few thousand device calls.

**The strided tier exists because equal-shape refusal is a different call**: at an equal shape
`--simd` runs a lane loop, but a BROADCAST is a scalar odometer walk in every `--simd` backend
(`.kb/linalg-simd.md`). So `linalg:sub` is a member against `(4 256 1)` and a decline against
`(4 256 384)`.

**A DECLINED strided call must allocate nothing -- an ORDERING rule in the interceptor**: the size
test over a free bound comes FIRST, ahead of the broadcast-shape derivation and permutation check
(both allocate an `int[]`), because `linalg:add`/`sub`/`mul`/`div` mostly decline.

## The accept rules against the shapes the programs run

An interpreter census (counting hook on `LinalgGpu.define`) answers for both offer layers, which
`GpuOfferDifferentialTest` pins to agree. The encoder-decoder Transformer's declines are
`%la-scaled-masked-softmax` refusing a padding mask (`f(64 1 19)` against `f(64 19 19)`, not a suffix);
llm's `vec:matvec` declines on SIZE at `f(288 288)`. The reference GPT's `(1 256 256)` mask is a
`double[]` over a `float` score -- **the only production shape exercising the either-width mask
clause.**

### The device contract, read against the implementations

No implementation narrows any clause of `GpuDevice`'s javadoc (masks of either width or `null`, all
four `gemmT` orientations, `offeredMatvec`'s bounds BEFORE the size and residency gate, every kernel
entry `try { ... } catch (Throwable) { return false; }`). Every shape-level accept rule lives in `Gpu`;
`CudaGemm` adds only `usable` and allocation failure.

**No PRODUCTION code does arithmetic on a threshold, and none may.** Metal's fold threshold is
`Long.MAX_VALUE`; `2 * Long.MAX_VALUE` wraps NEGATIVE. `Gpu` only compares (`>=`). **A threshold is
safe to compare against and unsafe to compute from.** A mask neither `float[]` nor `double[]` is an
explicit refusal in every kernel entry (one spelling once failed open as a scalar mask).

## Precision

Four breaks with the scalar defun, of different kinds; the GEMV's is in "The GEMV, and the matrix that
stays".

**The product FUSES.** One accumulator per output cell, `k` ascending -- the defun's order -- but the
multiply-add compiles to `fma.rn`, rounding once where the defun rounds twice: **3.4-5.6e-16 at f64,
2.1-9.0e-7 at f32** of the largest cell, and at f32 that is where any CPU f32 accumulation lands (**the
divergence is the WIDTH, not the GPU**). Exact-at-width inputs match EXACTLY. A tuned BLAS agrees bit
for bit up to n=128 and separates from n=192 (OpenBLAS blocks `k`), so
`theDeviceIsAskedAheadOfATunedBlas` uses n=192. Pins: **1e-12 relative at `#d`, 1e-5 at `#f`** --
never flapping, yet fast-math, a mis-numbered op code or a lost `-arch` fails instantly.

**The strided, resident, index and copy tiers are BIT-IDENTICAL to the scalar defun**: read widened to
double, compute in double, narrow only on the store, no libm. On Metal the claim is EARNED by the
software binary64 route. `sqrt`'s NaN: the device signs it and `Math.sqrt` does not, so the kernel
canonicalizes.

**The clip norm CANNOT be bit-identical, and says so.** `%la-sum-squares` is a whole-array reduction
with ONE output cell. (Rejected: a fixed blocked order on both sides -- no single order is good on both
machines; leaving it on the host -- 278 MB a run of downloads.) Each block folds a grid-strided slice in
a `double` with every term rounded where the defun rounds it (`__dmul_rn`, `__dadd_rn`); the host adds
the partials in block order from the caller's seed. Block count `min(1024, ceil(n / 256))` is a function
of the length, so the answer is REPRODUCIBLE. **Contract: `torch:clip-grad-norm`'s norm under `--gpu`
is within a few ulps of every other backend's, not equal.**

### The check that replaced byte-identity

**`--gpu` is the first flag whose results should not be expected to match other backends elementwise**
(the guide says so). Three checks replace byte-identity:

1. Byte-identity asserted wherever the device is not asked -- below thresholds, refused members at any
   size, an equal-shaped binary pair at any size -- **and for the whole strided tier where it IS
   asked** (`theStridedTierIsByteIdenticalWithTheFlagOnEveryMachine`).
2. Above the threshold, the relative tolerance per element on EVERY machine (zero difference without a
   device, so one test carries both worlds).
3. **`CUDA_VISIBLE_DEVICES=` makes every flagged run byte-identical to an unflagged one.**

`--gpu` **stays out of `ci-spec.yaml`**; the scalar `linalg.lisp` defun remains the oracle.

## Device residency: the arrays stay on the device

**Do not change any part of this without reading the two enumerations below**: they make a cache of
device copies sound, and each has a pinning test on each backend.

`DeviceResidency` maps a host `double[]`/`float[]` by IDENTITY to a device copy of a span (`offset`,
`bytes`; a different span is a miss). The handle CANNOT live in the array: on the JVM class output the
array IS a bare `float[]` with its header inside. Identity is sound because
`make-array :displaced-to` rejects a packed array.

**Buffers are freed only at the start of a call (before any operand lookup) and at its end**: a
stream-ordered free enqueued between an operand's lookup and its launch would run ahead of the kernel.
`Gpu.written(host)` drops an entry and QUEUES its buffer without a driver call.

**The keys are WEAK.** Strong keys kept every activation and gradient reachable: heap to 14 GB, the
step 2.3x slower. A collected key turns up on a `ReferenceQueue` and the next drain frees its buffer;
an access-ordered `LinkedHashMap` is the LRU; a lookup presents a transient `Lookup` so it allocates
no reference.

**A device copy to or from a host page the GPU has never touched costs ~9 us per 4 KB**
(`FreshPageCost.java`); a CPU copy INTO a page warms it. Hence:

1. **Every DOWNLOAD is staged** through one pinned 16 MB bounce buffer (allocated at probe time, so
   every leak test's baseline includes it). The UPLOAD's source was just written by the CPU: warm.
2. **The eager budget is a CAP on the pool, not a share of the card**: `min(free / 4, 1 GB)`. At a
   quarter of free memory the run was SLOWER than with no residency; 64 MB-1 GB were 5-10% faster.
   **The cap keeps the pool recycling warm blocks; it is not a safety margin.** The interceptors run
   LAZY results, budgeted by the headroom rule below.

**Residency may slow a call by one upload but must never turn it into a decline**: the pre-flight
evicts everything the call is not holding, trims, and asks again before refusing.

### A budget below the working set is the one thing the library says out loud

That cliff is the single shape in which `--gpu` LOSES to not passing it, and it looks like an ordinary
slow run. `Gpu.reportResidencyPressure(prefix)` registers one shutdown hook printing
`Gpu.residencyPressure()` to `System.err`; both interceptors ask with `"--gpu: "` (the compiled program
from its emitted `_gpuInit`). The library is otherwise silent for any embedder that does not ask.

**The signal is the RE-UPLOAD, not the eviction** -- a dead activation evicted and never asked for
again is lazy mode working, so an eviction count would cry wolf every training step. Evicted keys sit
in `evictedForSpace` (weak, bounded 4096); a `put` finding its host there counts a re-upload; `written`
strikes it out (a rewritten matrix is a fresh upload by design). The pre-flight's `evictAll` is NOT
counted (one call needing the whole device). The report fires once `reuploadedBytes > budget`; a budget
of 0 never reports. Verified on both halves by `ResidencyCliff.java`.

**Not built**: taking a budget-evicted matrix COLD instead of declining -- measured only on UNIFIED
memory, where it reverses the two-sight rule's premise; it needs a discrete card. No budget knob.

### The two seams, and what must report through them

**Every in-place write to a packed array's storage must come through `written` BEFORE it lands**, or
the next call answers for stale bytes. Before, not once per write: a writer that proves nothing
re-uploads the array between its first and last store may report once -- the JVM typed loop does
(`.kb/jvm-typed-loops.md`); per store cost half of a `--gpu` decode step. **Every host READ of packed
storage must come through `materialize` first**, or it reads the zeros of an array nobody filled. Both
are a volatile read plus an identity compare when they do not matter, and never probe.

**The interpreter has ONE seam of each.** `LispSingleFloatArray`/`LispDoubleFloatArray` call
`FloatArrayAccessHook.written` from `setElement` and `.read` from `data()` -- so `aset`, `fill`,
`aref`, the printer, `read-sequence`, record patterns and Java interop all pass. The hook lives in the
ROOT package and must not name any accelerator (`-Pweb` substitutes `eval/LinalgGpu`, whose `install`
points it at `LinalgGpuKernels`). The device interceptor itself takes `storage()`, not `data()`.

**Three kinds of writer bypass the setter and report themselves**: the `--simd` in-place kernels
(`%la-adam-step`, `%la-scatter-rows`, `%la-scale`, `%la-rng-fill`), `vec:`'s `-into` family, and the
bulk `%read-sequence-packed` (fills through a `FloatBuffer` view, so a grep for writes via `.data()`
sees a READ; it is how every model weight arrives). `torch:set-data` looks like a writer and is not: it
REBINDS the field.

**The JVM class output has no seam and ENUMERATES** `_gpuWritten`/`_gpuMaterialize` calls:
`_fvAref1/2/N`, `_fvAset1/2/N`, `_fvToGeneral`/`_fvToGeneralPrint`; every
argument of every accelerated `linalg:` call site (after the device attempt, before any host rung) and
of every `vec:` call site; a typed loop's `hoistArrays` (materializes EVERY array, then reports written
each array the body STORES into, once, in that order -- two array variables can be one object);
`_readSeqPacked`/`_writeSeqPacked`; every argument of a Java interop call.
`_fvDims`/`_fvLength`/`_fvElementType` read the header only.

**The one reader that cannot be enumerated** is `runtime.RontoFloatArray` (the `rontolisp:jvm-export`
handle, outside the generated program): it resolves `_gpuMaterialize`/`_gpuWritten` reflectively
(`.kb/jvm-export.md`), which is why `floatArrayResult` does NOT materialize.

**The pins**: `everyEnumeratedWriterInvalidatesTheResidentCopy` and
`everyEnumeratedReaderMaterializesTheDeviceResult`, on EACH interceptor. **Extend these when a new
in-place writer or raw reader of a packed array is added anywhere.**

### Lazy results, and the result that has no host array

`Gpu.lazyResults(true)` makes every member skip the download: the result buffer becomes the array's
DIRTY copy, which comes home only through `Gpu.materialize`; a clean copy stays for the next member, so
`matmul -> div -> where -> softmax -> matmul` moves nothing over the link. Off by default (the
library's contract "`out` is filled when the call returns" holds for other embedders); the
interceptors call `Gpu.lazyResultsIfWorthwhile()`, which asks `GpuDevice.lazyResultsPay`.

**The device never drops a dirty copy on its own.** LRU eviction, the pre-flight's `evictAll`, a
replacement at another span turn a dirty entry into a `Flush` (host array held STRONGLY), downloaded
IMMEDIATELY after the call that produced it -- in between, a reader would see nothing to materialize.
Its pointer is QUEUED, not freed, because an eviction inside `stage` precedes the launch reading it.
The LRU evicts CLEAN copies first; `lazyResults(false)` brings every dirty copy home first.

**The lazy budget** is everything the device has less an eighth (`LAZY_HEADROOM_SHARE`, floor 512
MB): with the 1 GB eager cap the autograd graph's live activations were evicted as fast as they were
made, and lazy was SLOWER than eager.

**A lazy result allocates no host array: it is a STUB** -- the header alone on the JVM, an EMPTY
`float[0]`/`double[0]` on the interpreter, distinct per result. Header-only readers (`array-dimensions`,
`length`, `array-rank`, type predicates, the printer's prefix) work unchanged, and the stub is the
identity residency keys on. Elements live on the device while dirty and, from the first host touch, in
a library-allocated BACKING (`DeviceResidency.storageFor`, a second weak-keyed map). Four rules:

- **Stub vs full array is STRUCTURAL** (`Gpu.fitsResult`: `length == offset` is a stub; long enough
  for the span is full; anything between declines).
- **A stub OPERAND has the extent of its span** (`GpuDevice.extent`); asking `a.length` made every stub
  decline.
- **Three states, never a fourth**: dirty copy, no backing; copy and backing; backing alone. Neither is
  a broken invariant and `source` throws rather than uploading zeros.
- **The two seams ANSWER the array to use** (`Object`: the host array or the stub's backing). On the
  interpreter `data()` answers what the hook answers, except the in-place `--simd` kernels report
  `storage()`. On the JVM every enumerated site REBINDS its local, and `_fvLength` at rank 1 reads
  `d[1]`, not `d.length - 2`.

**The unswap rule: a host rung that answers its argument answers the CALLER's object.** The argument a
host rung got is the BACKING; if it escapes, a device member offered it keys a second entry a write
through the stub never invalidates -- a silent stale read. Call sites map the answer back through
`_gpuUnswap(result, original, handed)`. The interpreter's value is the RECORD, so no problem there.
**The one hole, named rather than closed, is Java interop.**

**The fast paths remember FOUR arrays, not one** (a loop reading one array and writing another took
the monitor every element with one). The read ring holds `(host, storage)` PAIRS as one immutable
object per slot, so a racing reader never sees a mismatched pair.

### The tiers that exist only over a resident operand

**Offered ONLY over an operand the device already holds** (`Gpu.resident`, no hit count), declined
otherwise at any size, so the round-trip refusals' measurements stand. All bit-identical to the CPU
kernels. Size-thresholded members also take a resident operand at ANY size (`worthOrResident`).

| member | `linalg:` shape | kernel |
|---|---|---|
| `zip(op, a, b)` | the eleven binary ops at an EQUAL shape | `zip_fXX` |
| `scale(op, a, s, swap)` | the same with a SCALAR on either side | `scal_fXX` |
| `map(MAP_SQRT .. MAP_SIGN)` | `sqrt` `abs` `negative` `sign` | map cases 12..15 (`MAP_LIBM_OPS` = 12 ends the size threshold) |
| `where(m, x, y)` | `linalg:where`, hence `torch:masked-fill` | `where_fXX` |
| `adamStep` | `%la-adam-step`, IN PLACE | `adam_fXX`, every step an `_rn` intrinsic so nothing contracts into an FMA |
| `copy` | `reshape`/`expand-dims`/`squeeze`/`flatten`, rank-2 `transpose`, `%la-gather-strided`, `concatenate`, in-place `%la-scale` | `copy_fXX` |
| `takeRows` / `gather` / `scatterRows` | `take-rows`, `gather`, `%la-scatter-rows` | `take_fXX`, `scatter_fXX` |
| `sumSquares` | `%la-sum-squares` behind `torch:clip-grad-norm` | `sumsq_fXX`, the ONE member whose fold order is not the caller's |

**`scatter_fXX`: one thread per DESTINATION cell, not per source element.** The CPU adds slab `i` into
slab `idx[i]` for `i` ascending and embedding indices repeat, so the order IS the value and atomics
would lose it. `Gpu.scatterRows` stably counting-sorts indices by destination; thread `(r, k)` walks
its own group in the defun's order. The destination is a fresh zero table: 0.2-0.4 MB up instead of
1.9 MB down.

### The GEMV, and the matrix that stays

`vec:matvec` is the first member outside `linalg:`. Memory-bound per CALL, it wins per TOKEN once the
matrix stops moving.

**The rule that decides the upload is not a size.** The first sight of a matrix declines and leaves a
MARK (no buffer, no budget, cleared by `written`); the second unwritten sight of the same span uploads;
later ones hit. Weights are resident from their second token; a matrix REWRITTEN between calls (a KV
cache) is first sight every time and never pays the cold trip it would lose (0.87x at 384x384 f32).

**The accumulator: a double at `#d`, a COMPENSATED FLOAT-FLOAT PAIR at `#f` and `#bf16`.** Over 1024
rows of 768 inexact floats a double accumulator matches the defun on 1024 rows, a plain float one on
268, the `--simd` lane kernel on 144. But on a GB10 one double FMA per element is a COMPUTE ceiling
(~70 G/s, the fp64 rate) the bf16 kernel hit at 138 GB/s whatever the load width
(`Bf16KernelProbe.java`, `gemv-bf16-probe.cu`). The pair (product error recovered by an fma, every
addition a TwoSum, the warp fold pair-wise; `gemv_ff<T>`, spelled as `gemm.metal`'s `gemv_f32`)
carries ~48 bits at the fp32 rate and matched the double-accumulated oracle on EVERY row at seven
shapes up to 248320x1024; the 508 MB head went 3.67 -> 2.22 ms at bf16. It is what keeps llm's story
byte-identical with the flag on. Pinned as a relative tolerance plus ">99% of rows identical".

**bfloat16 is the third matrix width, of this member only**: a `#bf16` matrix (`short[]`) against an
`#f` vector into an `#f` result, the CPU fused kernel's pairing (`.kb/bfloat16.md`). `gemv_bf16`
widens each pattern (one shift, exact) and is otherwise `gemv_f32`, so **it IS the f32 kernel over the
widened matrix bit for bit**
(`GpuTest.aBfloat16MatrixByVectorProductIsTheSingleFloatKernelOverTheWidenedMatrixBitForBit`).
`GpuDevice.supportsBfloat16()` is `false` on Metal (a hard decline). The bridge's `bf16Dim` reads the
TWO-slot header (`[rank, hi_0, lo_0, ...]`). Any other pairing declines to the rung below; `--gpu` may
not turn an answer into an error. Bandwidth-bound from 6144x1024 at 1.9-2.9x the f32 kernel; the cold
trip never pays. Decode effect: `examples/llm/README.md`, "bf16 weights on the device".

**Q8_0 is the fourth matrix width, and the one member of the flag pinned to the scalar defun's BITS**
(a `rontolisp:quantized-matrix`, `.kb/quantized-matrix.md`, against an `#f` vector). `gemv_q8_0`
spells the CPU contract (four f32 lane accumulators in block order, folded
`(acc0 + acc2) + (acc1 + acc3)`, every step `__fmul_rn`/`__fadd_rn`). The activation is quantized on
the HOST (`Gpu.quantizeActivationQ8`, the third transcription of the rule, pinned by
`GpuDeclineTest`; a device quantize kernel cost +4.2 us a GEMV against 0.7-2.5 us). `GpuTest` asserts
every row's raw bits against the contract; both interceptor suites assert equal prints under `--gpu`,
`--gpu --simd`, `--simd` and neither. `GpuDevice.supportsQuantized()` is `false` on Metal;
`DeviceResidency` keys the `byte[]` at width ONE; `JvmGpuTemplate.gpuMatvecQ8` is the third place
spelling the int header (`qmOff` / `qmDim`); `PackedBuffer.load` reports a `read-sequence` into the
blocks to the write hook. **The activation buffer is kept across calls** (`CudaGemm.q8Activation`):
two driver round trips a call saved. Rejected kernel shapes: four threads a row (half the bandwidth), a
shuffle transpose-reduce (+15%), `__dp4a` (no gain); a warp-per-row fold is faster and NOT the
contract's bits. Qwen3.5-0.8B forward: 13.9 ms against bf16's 16.7 (1.20x).

**A decode forward is linear in the GEMV kernel time, one for one** (`-w f32` against `-w bf16`: 5.1-7.1
ms for a kernel difference of 5.5-6.3), so a narrower width buys exactly what it takes off the kernels.

**No row-count argument on `vec:matvec`.** The value cache is TRANSPOSED, so its half of the attention
pair would want a COLUMN bound: one member, two kinds of bound, on every backend and flag, for a caller
that can allocate what it uses. **A bound that one operand of the pair cannot spell is a bound in the
wrong place.**

**A `--gpu` step is mostly NATIVE time, so a percentage taken off JFR's execution samples is a
percentage of the wrong denominator** (`jdk.ExecutionSample` vs `jdk.NativeMethodSample`: 217 against
1471 in one step). **Quote the two sample sets together or neither.**

**The downcall handles stay INSTANCE fields, measured**: a `static final` handle and
`CudaDriver`'s `final` instance field reached from a `static final` are both ~9 ns on Graal (it
constant-folds `Gpu.Probe.DEVICE` -> `CudaGemm.driver` -> the handle), 0.7 ns apart on C2, and ~2 us in a
native image where no arm is a constant.

**The seam is a CHAIN on both backends.** Interpreter: `LinalgGpu.installVec`, from the VEC library's
lazy-load hook after `VecSimd.install`; it installs the write hook itself since a program may never
reach `linalg:`. JVM: `JvmExprCompiler` routes `vec:matvec` to `JvmSimdCompiler.compileGpuMatvec`
whenever the GPU bridge was emitted, with `--simd` or without. Declined: anything but a rank-2 matrix
(packed or Q8_0) and a packed rank-1 vector at one of the four pairings with matching extent, and the
first sight.

### The collector, and the flags that do and do not help

**On CUDA the library ASKS for a collection**: with only DIRTY copies left over budget the LRU STOPS
and sets `collectionWanted`; the owner runs `System.gc()`, drains, then flushes what is still over. At
most once per eighth of the budget PRODUCED (`COLLECTION_SHARE`, floor 64 MB). `-XX:+DisableExplicitGC`
makes the reference-shape run 4.5x slower; what a collection costs is the pages
(`-XX:+ExplicitGCInvokesConcurrent`, never compacting, recovers all of it).

**The heap's pages have to be ones the program recycles.** **Hand-size a young generation only where
the program FILLS it** (`-XX:+UseParallelGC -Xmn8g` where a step allocates gigabytes); **otherwise add
`-XX:+ExplicitGCInvokesConcurrent` to a long run**. Traps: `-Xmn` sized for the wrong shape is worse
than none (57% slower); **`-XX:+AlwaysPreTouch` under G1 is 4x slower** (G1 pretouches every
expansion inside the pause).

**On Apple silicon this does not transfer**: under the asynchronous lazy build the default collector
answers best or tied (`-XX:+DisableExplicitGC` costs 4-10%). **On a Mac: set `-Xmx` and stop** -- it
sizes both the heap and the pool the results live in (working set less the heap).

## The CUDA backend

**Fifty-two entry points in `gemm.cu`** (eleven in `gemm.metal`: no f64, no generator, no fused tier),
each taking its member as an op-code PARAMETER. A batched kernel offsets the pointers by
`blockIdx.z` times the strides and calls the SAME `gemm<T>` device function, so a batched cell folds
`k` bit-identically to an unbatched one. `batch == 1` launches the PLAIN kernel.

**One stride per operand, not an offset table -- and the decline that buys.** The CPU walks batch axes
as a mixed-radix odometer; the device adds `blockIdx.z * stride`. They agree for a contiguous batch of
any rank and a wholly broadcast operand, NOT for a broadcast axis under a non-broadcast one --
`(2 1 40 40) x (2 3 40 40)`. The interceptors derive the stride in O(rank) and answer -1 (a decline)
when no single stride reproduces the odometer.

**The strided layout rides BY VALUE in the parameter block on both backends.** In a pooled buffer the
192-byte `cuMemcpyHtoD` was synchronous, so every strided call was a hidden `cuCtxSynchronize`. It is a
fixed `strided_meta` struct (`4 * Gpu.MAX_STRIDED_RANK` = 64 ints, tail zero, because `cuLaunchKernel`
copies the declared size). `take` and `scatter` keep index BUFFERS (no fixed size).

### The register-tiled f32 GEMM

`gemm_tiled<T, TM, TN>`: a `16*TM x 16*TN` tile, 16x16 threads, each owning rows `ty + i*16` and
columns `tx + j*16` (coalesced global access, conflict-free shared reads), staged 16 deep in k.
`gemm_batched_f32_t4` (64x64) and `_t8` (128x128). **f32 ONLY**: at f64 the scarce double units pin
every tile to one speed and the 8x8 tile spills and LOSES.

### The launch pipeline, and what a step is bound by

The step is **device-bound**. The two hidden host-side serializers (post-launch `cuCtxSynchronize`,
the strided layout copy) are gone; survivors drain through `awaitQueued`. Launches are ~2.5 us of API
and overlapped, so **the gap to PyTorch is not launches**: its "eager fp32" GEMMs are TF32 tensor cores
(a precision class deliberately not used) and the rest is PASS COUNT, which the fused tier answers.
**The zero-copy route was measured and NOT taken** (`ZeroCopyRoute.java`, four alternatives); re-run
that table before any change.

## The fused tier

The exact GELU, softmax, log-softmax, layer-norm (and its affine), the scaled+masked softmax and the
dropout mask, forward and backward, each ONE kernel where `torch.lisp` launched a chain of `linalg:`
members. **What a fused kernel buys on CUDA is only the passes it removes**; launches were already
pipelined.

**The contract is the chain's, rounding for rounding**: every member boundary's rounding (the `(T)`
casts), every axis fold ascending and sequential in a double, `exp`/`erf` at the width as `map_op`.
Pins: `GpuTest.theFusedTierLandsOnTheComposedDeviceChainsBitsAtBothWidths` (member by member vs fused,
equality); the libm-free three land on the CPU defun's bits
(`theLibmFreeFusedMembersAreTheSequentialReferencesBits`, against `GpuTest.layerNormGradReference`). The
GELU adjoint measures 1.8e-12 relative at `#d`, pinned at 1e-9.

**The members are the compositions on every backend** (`.kb/torch.md` "The fused compositions"): the
internal `linalg` defuns (`.kb/linalg.md`) ARE the chains, so no CPU path changed
(`TorchGradcheck.FUSED_PROGRAM`, ci-spec `torch-fused-compositions`). The two adjoints that fold onto an
accumulated gradient take it as an operand (`OLD`, null when none).

**The offer rule is the chain's.** A fused member with a libm call (GELU, its adjoint, softmax): the
map threshold or a resident operand; the libm-free ones: the fused threshold or a resident operand,
with the fold's cell rule on the row count; the mask as `rngFill`. Operands the rule is not about (the
attention mask, layer-norm's weight and bias) are bounds-checked and do not decide.
`linalg:softmax`/`log-softmax` are intercepted in their `:axis` form over the LAST axis only, an
EXTENDED call shape on the JVM (`LinalgKernelCallLayout`).

## The tape-side fusions this tier depends on

- **The transposed product.** `ta`/`tb` mark an operand STORED transposed; the staging load indexes it
  so and the fold reads the same tile, so **the product is bit-identical to the plain product of the
  transposed copy**
  (`GpuTest.aTransposedOperandIsReadInPlaceAndFoldsOntoTheUntransposedProductAtBothWidths`). Shared
  tiles padded to `TILE + 1`; padding the REGISTER tiles LOST. **The seam is two members, not a flag**
  (`linalg::%la-matmul-nd-ta` / `-tb`, arity 2): flag arguments would need a new extended call shape on
  the JVM. Their portable defuns are the transpose and the product, so other backends are untouched.
- **The attention scale and mask** are one node, `%la-scaled-masked-softmax` and its `-grad`. Each cell read as `(T)(x / s)` then `fill` under the mask, both roundings
  reproduced. **The mask must be a TRAILING block of the operand** (leading 1s dropped, a suffix of the
  operand's dims), either width; anything else declines to the defun. **Fusing does not cost the kernel
  nothing**: the row kernels run one thread per row, so a mask load per cell is exposed latency costing
  what the removed `where` pass did. **The mask therefore reaches the row kernel PACKED, one bit a
  cell** (a `pack_mask_*` launch just before, 8 us; lanes exchange bits by shuffle). A `__shared__` tile
  per template instantiation cost the PLAIN softmax 30%, so tiles are declared once, in the dispatcher.

**A division by a power of two is launched as the multiply** (`div.rn.f64` is what this card is slow
at; the two are two correct roundings of the same real for every operand). `Gpu.scale` rewrites
`BIN_DIV` with an exact reciprocal to `BIN_MUL`. **The reciprocal must be normal at BOTH widths**
(`Gpu.exactReciprocal` / `Gpu.normalPowerOfTwo`), or Metal's `float` would multiply by an underflowed
zero. Pins: `aDivisionByAPowerOfTwoIsTheExactReciprocalsMultiplyAtBothWidths`, `GpuDeclineTest`.

### The decline that was only a materialize

The `linalg:` call site is a chain -- device, `--blas`, `--simd` lane, scalar defun. `_gpuMaterialize`
over the arguments exists for the LANE and LIBRARY kernels, which read raw storage; **the defun does
not need it** (compiled Lisp reports its own reads). Emitting it unconditionally dragged home a
device-only member's score that the device read next (288 round trips a step, 0.491 -> 0.416 s).
**The guard is emitted only where a host KERNEL rung follows** (`simd != null || (blas != null &&
!extendedCall)`); an in-place member's write report is emitted always.

## The Metal backend

Shared: the flag, the CLI, the interception layer, the decline protocol, the tests. Not shared: the
width, every threshold, two tiers.

| | CUDA | Metal |
|---|---|---|
| widths | `#d`, `#f`, `#bf16` and Q8_0 as the GEMV's matrix | **`#f` only** -- MSL rejects `double` |
| rank-2 product | our tiled kernel | **MPS** above `2^27` per matrix, ours below |
| stacked product | our batched kernel | our batched kernel |
| transposed product | `ta`/`tb` | the same flags, and MPS's transpose above the MPS threshold |
| fused tier | nine members | **eight** -- the dropout mask stays declined |
| element-wise, broadcast + axes transpose | yes | yes |
| axis fold `:axis` | yes | **not as a round trip, measured**; resident only |
| generator fill | yes | no -- needs a `double` |
| `vec:matvec` | from `2^17` | from `2^21`, `#f` only |
| lazy results + resident tier | on | on (asynchronous command buffers) |
| resident set | every operand and result | eagerly **the GEMV's matrix only**; lazily all |
| index tier + clip norm | yes | NOT members (kernels never written) |
| per-call floor | 16-18 us | **77 us** per COMMAND BUFFER eagerly; **15-26 us a member** in a lazy chain |
| per-call memory | the driver's pool | **our own** size-classed pool |
| kernels | PTX checked in | MSL compiled at RUN time from a string |

**Single float, or nothing.** Every double-taking method answers `false` without touching the device;
the rule is about operands that ENTER ARITHMETIC (`where`'s mask is taken at both widths). So **the
decline protocol is load-bearing here** (`linalg`'s default width is double; the flag is inert until a
program reaches `#f`), and `GpuTest` is gated on a double-capable device while `MetalGpuTest` answers
the same claims at `#f`.

**The rank-2 product goes through MPS and the STACK does not.** The routes agree BIT FOR BIT, so the
choice is pure size. The stack stays on our kernel because a broadcast operand's stride 0 cannot be
handed to a batched `MPSMatrixDescriptor`. Bit-identity lets `rowBytes` be `columns * 4` rather than
`rowBytesFromColumns:dataType:`, which PADS.

**The axis fold is not a round-trip member here, for two independent reasons**: `%la-fold-axis`
accumulates in `double`, so a float accumulator is ~1e-5 off over a 256-long axis; and amax/amin is a
tie at best (CPU 85 us over 262144 elements against ~150 here). Threshold `Long.MAX_VALUE`. Over a
RESIDENT operand `fold_f32` exists.

### Precision on this backend

**The strided tier's bit-identity is an ARGUMENT here, not an inheritance.** `+`, `-`, `*` of two
floats are EXACT in binary64, so rounding once to float equals compute-in-double-then-narrow; `/` is
innocuous double rounding (53 >= 2*24 + 2); selects and gathers move values.

**Where the argument does not reach, the shader runs IEEE binary64 in SOFTWARE** (a non-float scalar,
every Adam step, the sum fold), one rounding step (`f64_pack`) shared by every operation.
`GpuDeclineTest` asserts no code line of the file says `double`; the emulation is spelled `f64`.

**This GPU flushes subnormal floats to zero in every float operation, `MTLMathModeSafe` or not.** Every
float kernel guards it (`bin_op_exact`): a subnormal operand or a result below `FLT_MIN` is recomputed
on the binary64 route; `abs`/`negative`/`sign` are bit operations, amax/amin compare through an order
key, `where`'s test is a bit test. **`sqrt` needs `precise::sqrt`**: plain `sqrt` is 1 ulp off in ~10%
of operands.

**Two transcendentals FIXED rather than tolerated**: MSL's `tanh` and `sinh` have an absolute error
floor (~3.4e-8) so relative error is unbounded as x -> 0; below |x| = 1/4 `gemm.metal` takes the
Maclaurin series to `x^9`. **`erf` has no builtin**: the shader runs `%la-erf-1`'s own series, closer to
the oracle than CUDA's.

Pins: `theStridedTierIsBitIdenticalToTheScalarOracle`,
`theSoftwareBinary64RouteLandsOnJavasDoubleArithmeticBitForBit`.

### The fused tier on Metal

Eight of the nine, one thread per row, made possible by the software binary64. **Every member
boundary goes one of two ways, not by choice**: a
boundary of two floats is that rounding once (`bin_op_exact`); a boundary against a constant the float
grid does not hold (`1/sqrt 2`, `2/sqrt pi`, layer-norm's `eps`) takes the software route, as the
chain's `scal_f32` does. `MetalGpuTest.theFusedTierLandsOnTheComposedDeviceChainsBits` asserts EQUALITY
over RESIDENT operands. **A third off the step** (CUDA: a quarter): it also removes four command
buffers out of five.

**`GpuDevice.Thresholds` has a `fused` field** because `Gpu.offeredRows` took the FOLD threshold, which
is `Long.MAX_VALUE` here: CUDA passes its fold threshold, Metal `MIN_MAP_ELEMENTS`. Folds are
sequential `f64_add` in index order, so `MetalGpuTest.theLibmFreeFusedMembersAreTheSequentialReferencesBits`
holds; `row_tile` is a transposed LOAD, not a reduction. **One thread per row is a COST in the mask
fusion and a GUARANTEE here.**

**The dropout mask stays declined, on the ARITHMETIC**: Wichmann-Hill's uniform is three binary64
divisions an element, here the software restoring divide (also why `rngFillF` is not a member).
`MetalGpuTest.theDropoutMaskStaysDeclinedHere`.

### The map threshold at the straddling shape: the clock ramp

**This GPU lowers its clocks after ~1 ms idle, and the first command buffer after such a gap costs
~0.5 ms more** (`MtlPerRowMap.java`: `log` at 16384 f32, CPU 62-66 us, device 98-137 back to back,
419-510 behind the chain's own gap). A chain member whose operand a REFUSED member produced always sees
the gap, so the crossover is at 2^17..2^18, where the threshold is. **The straddle stays.** A size
threshold measured back to back is measured in the wrong context -- a Metal finding (CUDA is flat to
1%).

### Residency and the GEMV on this backend

**The accumulator is a compensated float pair**: a plain float sum matches 229 of 1024 rows, the pair
1024 of 1024. `#pragma METAL fp contract(off)` is what makes the error-free transforms exact.

**Threshold `2^21`; the COLD trip never pays**: on unified memory an upload is a memcpy of the bytes
the CPU kernel would have streamed, so the two-sight rule IS the member. The ~77 us floor dominates
until the matrix is several megabytes (1024x1024 a tie, 2048x2048 4.8x).

**Eager residency: only the matrix of an accepted GEMV.** The full CUDA design was 1-5% slower at every
cap: the upload it removes is a memcpy, while a slab held out of the pool costs a FRESH slab's
first-touch page faults. **`MIN_RESIDENT_ELEMENTS` = 2^14**, below the crossover, because a declined
member over a resident operand costs a materialize, the CPU loop and a re-upload.

### Asynchronous command buffers

**Lazy results are on here** (`MetalGemm.lazyResultsPay()` is `true`) because a call no longer waits:
the reference-shape step 4.80 -> 1.81 s, same loss to four decimals.

**The mechanism.** Lazily, `MetalGemm.commit` retains the buffer past the autorelease pool and stamps
every slab it held with a sequence number (`fence`). One queue executes in order, so "completed at or
below `retired`" is a scalar and `settle(slab)` is the only wait -- at `stage`'s upload into a
free-list slab (a launch in flight may still read it) and at every download. A RESULT slab needs no
wait.

**Failure surfaces at the first host read, never as zeros.** `retire` marks lost the slabs a failed
buffer WROTE and the results of later in-flight buffers that READ one; a slab it only read is intact.
A lost result throws at `materialize`; a lost flush throws at the read, so switching the mode off never
throws. Metal gives a kernel no way to fail on purpose, so
`aFailedCommandBufferSurfacesAtTheFirstHostReadOfWhatItWrote` injects the STATUS through a
package-private seam.

**Budget rules, each measured:**

1. **Size the pool WITHOUT the heap** (unified memory): working set less `Runtime.maxMemory()` less an
   eighth.
2. **Count the resident budget in the pool's units** (power-of-two slab CAPACITY, not spans): at seven
   eighths of the pool the LRU never fired and the pool's pressure path flushed EVERYTHING (10-12 GB of
   backings in one call). It is HALF the pool's (`LAZY_RESIDENT_DIVISOR`).
3. **The pressure path evicts a slab's worth at a time** (`DeviceResidency.evictSome`, LRU, clean
   before dirty). `drop` allocates a dirty copy's backing BEFORE letting the entry go, and puts it back
   if the heap runs out.

### Three further Metal findings

- **The strided layout rides by `setBytes:length:atIndex:`**, not a pooled slab (which a committed
  command buffer may still read). `Gpu.MAX_STRIDED_RANK` keeps it under `setBytes`'s 4 KB limit.
- **The scaled/masked softmax pair is TWO NEW ENTRY POINTS** (`softmax_sm_f32`, `softmax_grad_sm_f32`),
  not CUDA's dispatcher, because a second MSL entry point has its own threadgroup allocation and cannot
  cost the PLAIN kernels occupancy. Pin:
  `MetalGpuTest.theScaledAndMaskedSoftmaxLandsOnTheComposedDeviceChainsBits`.
- **A `where` mask is a PREDICATE, not a number** (`(/= m 0)` on the raw word), so the width rule does
  not apply: `where_f32` binds it as `device const uint*` (one word at f32, two at f64), staged at ITS
  width. It matters because `linalg:ones` builds DOUBLE, so every library attention mask is a
  `double[]`; a PADDING mask is not a trailing block and falls back to the chain containing this
  `where`. **Nothing else refused on width alone is reached by this reasoning.**
- **Layer-norm's affine was built, measured and NOT kept here**: the step does not see it (1.680 s
  declined against 1.702 fused) because the removed work overlaps host work. **Reopen it when the step
  is DEVICE-bound**; re-take the STEP, not the members, after every merge touching the path.

## The interception layer

| backend | interceptor | kernels |
|---|---|---|
| interpreter (native binary included) | `eval/LinalgGpu` (re-`defineFunction`) | `eval/LinalgGpuKernels` -> `am.ik.gpu` |
| JVM (`-o Prog.class --gpu`) | `codegen/jvm/JvmLinalgKernelCompiler` (call site) | `JvmGpuTemplate` -> the EMBEDDED `am.ik.gpu` |
| wasm-GC / `--no-gc` | no FFM -- a hard error | -- |

**A `.wasm` output REFUSES rather than ignores** (`RontoLispCli.compileRecorded`). `--gpu` is
value-less; `RontoLispCli.enableGpu` is `enableBlas` one layer up. **Nothing may ask
`LinalgGpu.available()` on a path that did not pass the flag** -- it runs the probe (~26 ms cold).

**The member set is an assertion** (nothing else is `defineFunction`ed; user-facing list in the
guide): `linalg:dot` over two packed rank-2 operands of one width, `%la-matmul-nd` and `-ta` / `-tb`,
the twelve libm element-wise members, the STRIDED tier at its one call shape each, `%la-rng-fill`, the
resident / index / copy tiers, the fused tier's `%la-*` members, and `vec:matvec`.

**The generator fill is the one member whose device result is the CPU's bytes at every size**, which
is what let it in: thread `i` jumps to its state by square-and-multiply over `a^k s mod m`, every step
an `_rn` intrinsic so nvcc cannot contract into an FMA; `Gpu.rngAdvance` advances the END state on the
host.

### The chain order, and why the device goes on top

On the interpreter the chain is INSTALL ORDER in `LispEvaluator.resolveFunction`'s lazy-load hook;
`LinalgGpu.install` goes LAST: `device -> library gemm -> lane kernel -> scalar defun`. Because
**`worth()` is probe-free and three orders of magnitude above `--blas`'s** (2^17 against 64), small
calls never touch the driver; where it accepts it is at worst level with a threaded CPU BLAS; and **a
declined member lands on the best CPU path the invocation asked for**. **`--parallel` sits strictly
BELOW the device decision on both backends.**

### The call site

`JvmLinalgKernelCompiler.compile` emits `_gpuInit(); _simdInit();`, each argument form evaluated ONCE
into a temp, then `gpuDot` / `blasDot` / `laDot`, each falling through on `null` to the scalar defun.
Every decline branch RE-READS the temps
(`anArgumentFormIsEvaluatedExactlyOnceEvenWhenTheKernelDeclines`, in all three suites).

- **The device attempt is per MEMBER**: `JvmLinalgGpu.kernelKey` maps each member to its `ops` key,
  which IS the bridge method name.
- **Extended (option-form) call sites carry a device rung too** (the axis folds and axes transpose are
  members ONLY in that shape), claimed when EITHER bridge has a kernel, with the SAME
  `LinalgKernelCallLayout` as the lane attempt; a shape with nothing to attempt routes to
  `compileDefault`.
- **The emit gate (`usesGpu`) is `programUsesSymbol` over EVERY member**, and **`--gpu` must NOT drag in
  the `--simd` bridge** -- such a class would need `java --add-modules jdk.incubator.vector`.
- **`-Pweb`**: `LinalgGpu.available` / `description` / `install` / `installVec` are the only entry points
  into `LinalgGpuKernels`, so both bindings drop out behind four substitutions
  (`Target_LinalgGpu`). **A new public method on `LinalgGpu` touching the kernels breaks it, and only
  the Pages workflow's Web Image build notices**; `./mvnw -Pweb compile` is the local check.

## The JVM backend: the whole library travels beside the class

The parts a copy would fork are the parts that were expensive to get right, so `JvmGpuRuntimeBuilder`
generalizes `--blas`'s template mechanism to a CLOSURE of classes plus data
([template-class-embedding.md](template-class-embedding.md)):

- every `am.ik.gpu` class is renamed by ONE prefix rule, `am/ik/gpu/` -> `<Program>$Gpu` (`Gpu` becomes
  `<Program>$GpuGpu`), in the program's package (members are package-private), shipped through
  `runtimeClassFiles()`.
- `JvmGpuTemplate` becomes `<Program>$GpuBridge` by the same pass: written and type-checked against
  `am.ik.gpu`, resolving to the shipped copies at run time.
- Named after the program because the library's statics (the probed device, the residency cache) are
  per program.

**BOTH kernel texts travel in every `--gpu` class whichever machine emitted it**, as string constants
in the program's pool; `_gpuInit` hands them to `Gpu.useKernels` / `useMetalKernels` before any probe.

### The offer is decided twice, and what pins the two

The DECISION to offer a shape does not travel: `eval/LinalgGpu` and `codegen/jvm/JvmGpuTemplate` both
sit above `am.ik.gpu`, so a disagreement runs `java -jar` and `-o out.class` down different paths with
nothing failing.

**The pin is `codegen/jvm/GpuOfferDifferentialTest`**, not per-helper assertions over the thirteen
shared predicates (`LinalgGpu.suffixLength` is `JvmGpuTemplate.softmaxMaskLength` on the other side).
It asks from OUTSIDE both: one operand set, each path's own call shape, agreement on accept vs decline
and, on accept, the same bits. Shapes sit at the accept BOUNDARY, and a census assertion fails if the
table did not both accept and decline.

**Only the member-SET half runs without a GPU**: every name in `JvmLinalgGpu.qualifiedMembers()` is
bound to a sentinel and handed to `LinalgGpu.install`, which throws on a mismatch either way. The SHAPE
half needs a device, structurally.

## Tests

| what | where |
|---|---|
| the library, a DOUBLE-capable (CUDA) device | `am/ik/gpu/GpuTest` |
| the library, a METAL device | `am/ik/gpu/MetalGpuTest` |
| the library on EVERY machine, both kernel texts | `am/ik/gpu/GpuDeclineTest` |
| native-image downcall registration, both drivers | `am/ik/gpu/NativeImageForeignConfigTest` |
| the interpreter's interceptor | `eval/LinalgGpuTest` (device), `eval/LinalgGpuDeclineTest` (every machine) |
| the compiled interceptor | `codegen/jvm/JvmLinalgGpuAccelCompilerTest` |
| the two paths' OFFER | `codegen/jvm/GpuOfferDifferentialTest` |
| value-less flag, REPL pair, `.wasm` refusal | `cli/CliOptionsTest`, `cli/RontoLispCliTest` |

**The dead-flag guard is the load-bearing one**: every numeric assertion would pass on the scalar
defun, and both print `#<function LINALG:DOT>`. What fails when the flag is DEAD is the TYPE --
`#'linalg:dot` a `LispFunction` (interceptor) vs `LispLambda` (defun) -- one assertion per member, plus
the list that must stay `LispLambda`. Compiled side: the bridge NAME in the class bytes.

- **`GpuDeclineTest` is the half CI actually runs** (every runner is GPU-less), so it must never
  regress; the one that matters is **that an op code the library does not name DECLINES**. **It hands
  the library the REAL checked-in text and no test may hand it anything else**: the override is
  process-wide and read at probe time.
- **`JvmGpuRuntimeBuilder.embeddedGpuClasses()` is pinned against the class files the build produced**
  -- a class added to `am.ik.gpu` must travel.
- **The interceptor suites derive shapes and width from the device in force** via the test-scope
  `am.ik.gpu.GpuThresholds` shim (`SIDE` 64 on CUDA, 208 on Metal; `MAP_N`; `TYPE` `single-float`
  without double).
- **Device-memory tests hold a `@ResourceLock`.** Five ask the POOL, two `cuMemGetInfo` against 1.5 GB;
  every leak run is sized so a real leak is 2-8x its bound.
- **An exact `residentBytes()` assertion must KEEP ITS ARRAYS REACHABLE, and a process-wide
  `dirtyCount()`/`backingCount()` diff around one call is not that call's effect**
  (`.kb/test-execution.md`); use `DeviceResidency.dirty(Object)`/`.backed(Object)`
  (`GpuThresholds.isDirty`/`.isBacked`).
- **Exact-input operands must be exact IN THE FOLD too** (the defun accumulates in f64;
  `.kb/linalg-simd.md`'s reduction contract).

### What `eval/LinalgGpuTest` costs, and the timeout that tells slow from stopped

It takes MINUTES where siblings take seconds, and has been killed as "hung" when it was not. The
oracle leg of every assertion is the interpreted defun at the DEVICE's floor, and Metal's floors are
4-16x CUDA's (`SIDE` 208 vs 64); the cost is the interpreted arithmetic, not the harness.

**`@Timeout(5, MINUTES, ThreadMode.SEPARATE_THREAD)`**: slow finishes, stopped fails with a stack.
`SAME_THREAD` would only interrupt, and the evaluator's loops poll no interrupt flag.

**Read per-method ratios, never class totals** (run-to-run spread moves untouched methods 20-50%).
**A margin over a threshold is paid IN FULL by every interpreted leg**, so `residentSide()` /
`matvecSide()` sit at 1.25x the gate (which counts the RESULT's elements, `Gpu#stridedCount`). Not
cuttable: the fused tier's twenty members and the stacked product's five shapes are distinct claims.
Not taken: parallelising the oracle legs (the residency counters are PROCESS-WIDE,
`.kb/test-execution.md`).

### The vacuity sweeps, and the rules they left

**A test whose shape does not clear the threshold gating the mechanism it asserts on runs nothing, and
passes** (`.kb/test-execution.md`). The backends' thresholds differ, so neither sweep answers for the
other; every finding was established by MUTATION.

- **`Gpu.worth(n, m, p)` applies `POOLED_MIN_WORK` (131072), not the offer's `Probe.MIN_WORK`
  (4194304 on the M4 Max)**: `Gpu.multiply` is the only authority, `GpuThresholds.minWork()` the number
  to size against.
- **Size an operand off the FINITE thresholds only** (a `2 * max(...)` over Metal's `Long.MAX_VALUE`
  fold wrapped to `-2` in `GpuOfferDifferentialTest`). Its twin
  `JvmLinalgGpuAccelCompilerTest.theFusedTierRunsOnTheCompiledBackendAndLandsOnTheChainsBits` asserts a
  bound, not bit-identity, for `linalg:log-softmax` against its chain.
- **Size a decline enumeration off the threshold of the tier it enumerates**
  (`GpuTest.everyStridedDeclineConditionStillDeclinesWithADevicePresent`: the fold conditions use
  `max(stridedMinElements(), foldMinElements())`).
- **A `...StillDeclinesWithADevicePresent` test opens by asserting the same shape ACCEPTED, over its OWN
  arrays** (an accepted call leaves its operand resident).
- **A resident-only member cannot be pinned with a fresh operand**:
  `theClipNormFoldsInBlocksOnTheDeviceCloseToTheSequentialSumAndReproducibly` builds its gradient through
  a broadcast add. The fused tier's ROW-COUNT floor (`Gpu#offeredRows`) is pinned with a total above the
  threshold in too few rows (128 x 2048 against 256 x 1024), the 256-row form asserted accepted first.
- **`eval/LinalgGpuDeclineTest`'s and `GpuDeclineTest`'s shapes are fixed and must stay fixed**: they
  are the half a GPU-less runner executes (and free device-PRESENT pins on a GPU box).

## Native image

Two build inputs in `src/main/resources/META-INF/native-image/am.ik.rontolisp/rontolisp/`:

- **`resource-config.json`**: `gemm.ptx` and `gemm.metal` TWICE -- conditional on the device class, and
  on `JvmGpuRuntimeBuilder`, because a binary that only COMPILES never reaches `CudaGemm` -- plus
  `JvmGpuTemplate.class` and `am/ik/gpu/.*\.class`, which the compiler reads as resources to embed.
- **`reachability-metadata.json`**: a `foreign.downcalls` entry per distinct SIGNATURE (both drivers and
  `--blas`, including the two-`MTLSize`-by-value `dispatchThreadgroups:threadsPerThreadgroup:`).
  **Without them the linker REFUSES the handle at BINDING time**, and the drivers bind every entry point
  in their constructor -- one missing shape reports "no driver" ON A MACHINE WITH A WORKING GPU. Guards:
  the drivers let a binding failure THROW; `NativeImageForeignConfigTest` binds both drivers against a
  lookup that finds everything and asserts every shape has an entry.

**A compiled `--gpu` output carries the same registration**: its image is built from the user's jar, so
`am/ik/gpu/reachability-metadata.json` (CUDA and Metal shapes only) ships as
`META-INF/native-image/rontolisp-gpu/<program>/reachability-metadata.json` beside the `$Gpu*` classes
(`JvmGpuRuntimeBuilder.nativeImageMetadataPath`); `NativeImageForeignConfigTest` pins it EQUAL to both
drivers' bound shapes. The opt-in `ShippedBridgeNativeImageE2eTest` pins the jar and the image.

Generate with the tracing agent over a program that opens the binding (constructing a driver registers
every shape). **Type names must be the agent's own** (`jlong`, `jint`, `jboolean`): un-prefixed aliases
parse, but `boolean` does NOT. **A per-entry `"comment"` key is rejected by the schema**, so the
signature-to-entry-point mapping lives in the top-level `comment` array.

## What is deliberately NOT here

Each is a measured decline; read this file's numbers before revisiting.

- **No element-wise member whose scalar cost is one machine instruction, AS A ROUND TRIP** (`sqrt`,
  `abs`, `negative`, `sign`, equal-shape binary ops). They ARE members over a RESIDENT operand -- the
  case the refusal's measurement never had. Re-run `ElementwiseCrossover.java` plus
  `elementwise-baseline.lisp` before offering any as a round trip.
- **No axis fold on METAL as a round trip.** The amax/amin half is the one to revisit if the floor
  drops; the sum half cannot come back while `%la-fold-axis` accumulates in double.
- **No index tier, no clip norm and no fused layer-norm AFFINE on METAL** (the first two never written;
  the affine measured and not kept).
- **Nothing of `vec:` but `vec:matvec`.** `vec:matvec-into` writes a CALLER's array; `vec:dot` loses to
  the lane kernel at every size. The first sight of a big matrix used ONCE is left on the table.
- **No zero-copy route, and no staged UPLOAD.** Measure with FRESH arrays before touching either.
- **No `static final` downcall handles** ("The downcall handles stay INSTANCE fields, measured" above).
  Revisit only with a profile quoting BOTH JFR sample sets.
- **An FFM downcall inside a native image costs ~1.7 us plus ~0.4 us per argument, and it is
  SubstrateVM's method-handle INTERPRETER** (GraalVM 25.0.4): `svm.jar`'s
  `Target_java_lang_invoke_LambdaForm.forceInterpretation()` returns `true`, so a handle created at run
  time is interpreted Name by Name, boxing every argument (SVM's `@InvokeCFunctionPointer` to the same
  address is 10.7 ns). **It cannot be made a constant**: a build-time-initialised handle makes the image
  build FAIL in `PolymorphicSignatureWrapperMethod.buildGraph` ("linkToNative"). Upstream:
  [oracle/graal#12219](https://github.com/oracle/graal/issues/12219) (the cost); the build crash is not
  the fixed [#9727](https://github.com/oracle/graal/issues/9727) and is unreported (texts in
  `.todo/artefacts/730-report-the-svm-downcall-findings-upstream/`).
- **The device thresholds HOLD in the binary; `--blas`'s did not.** A 64x64x64 product is 63 us in the
  binary against 30 on the JVM, still 2.4x ahead of the binary's lane kernel. `--blas` moved to
  `@InvokeCFunctionPointer` (`.kb/native-downcalls.md`); **the same route was REFUSED for the driver
  calls**: the binary's `--gpu` decode is the interpreter's, so driver interpretation is 0.06% of it.
- **No per-device collection policy** until the two backends' collection requests differ.
- **No Q4_0 / Q4_K weight width.** The CPU half is refused on its nibble unpack (ALU-bound, 1.1-1.35x
  f32 for 8.5% error, "The integer
  widths"). On the device a Q4_0 kernel with the Q8_0 bit contract at half the bytes saves at most ~2.7
  ms of a 13.9 ms forward, against a packed type, GGUF readers (Q4_K_M also carries Q5_K and Q6_K) and
  CPU arms on every backend. **Trigger**: a model whose bf16 GEMV time is the MAJORITY of its device
  forward (Qwen3.5-0.8B's is 40%), or a discrete card (all of this is unified-memory arithmetic).
