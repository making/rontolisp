# `--gpu`: a matrix product on the GPU, or a decline

Two layers. **`am.ik.gpu`** is a language-independent library that takes a member and either runs it
on a GPU or answers "no" -- CUDA through the driver API, Metal through Objective-C, behind one sealed
`GpuDevice` seam. **The interception layer** is the `--gpu` flag over it, on the interpreter and on
the JVM class output; a `.wasm` output refuses the flag outright and always will.

Read `.kb/linalg-simd.md` for the declined-input protocol and `.kb/linalg-blas.md` for the flag whose
posture this copies: **recommended, never required; a machine without the hardware runs the same
programs to the same output.** What is different about a GPU is the fixed cost of a round trip, a
separate machine with its own memory, and -- since residency -- that arrays stop coming back.

User-facing description and end-to-end numbers: `doc/{en,ja}/guides/gpu-acceleration.md` and
`examples/llm-from-scratch/README.md`. **This file is the invariants and the mechanics.** Every
number here is re-derivable from the probes in `.todo/123-gpu-acceleration/*.java` plus the
`*-baseline.lisp` CPU baselines (they load the shipped kernels, so they run wherever the feature
does) -- `CuLib.java` and the `Mtl*` files are the driver bindings, and the named probes are
`AllocatorCost.java`, `ElementwiseCrossover.java` (over `elementwise-probe.cu`),
`RngCrossover.java`, `FreshPageCost.java`, `ZeroCopyRoute.java`, `MtlPerRowMap.java`,
`MtlResidentFloor.java`, `MtlStridedFloor.java`, `gemm-tile-probe.cu`, `fusion-baseline.lisp` +
`fusion-segments.py`, `chains-baseline.lisp`, `elementwise-precision.lisp`,
`transformer-book-shapes.lisp` (`PEF=1` and `WIDEN=1` are its A/B knobs, `STEPS=n` its length),
`gpt-book-shapes-fast.lisp`, `mtl-attention-softmax.lisp`, `mtl-where-mask-width.lisp` and
`mtl-layer-norm-affine.lisp`; that directory's README says which answers which. The programs the
censuses are taken over: `examples/llama2` (`stories15M.bin`), `examples/ml/tiny-llm.lisp`,
`examples/ml/gpu-matmul.lisp`, `examples/llm-from-scratch/transformer` and `gpt`,
`train-gpt-soseki.lisp`, and `examples/deep-learning-from-scratch` ch05/ch07. The two calibration
machines: an **NVIDIA GB10** (Grace Blackwell, `sm_121`, 48 SMs, unified
addressing, driver 580 / CUDA 13, aarch64) and an **Apple M4 Max** (40 GPU cores, unified memory,
107 GB working set), both on Oracle GraalVM 25. A different device changes every number; the SHAPE of
each result should survive.

## The invariant

**`am.ik.gpu` never throws and never signals.** Every failure -- no driver, no device, an old card, a
shape it cannot launch, a member too small, device memory exhausted, any `CUresult`, a command buffer
that did not complete, an operand at an unsupported width, a JVM that forbids native access, a
platform with neither `libcuda.so.1` nor `Metal.framework` -- is `null` or `false`, and the caller
runs whatever it would have run anyway.

**Three deliberate non-declines.** A `null` operand array throws `NullPointerException` (the package
is `@NullMarked`). The array-returning `multiply` overloads allocate and so can throw
`OutOfMemoryError`; the `out`-taking overloads, which is what an interceptor calls, allocate nothing.
And **`Gpu.materialize` cannot decline**: when the host has no other copy of the bytes, a refused
download is an `IllegalStateException`, because silence there would be a wrong answer.

**Package rule** (as for `am.ik.jvm` / `am.ik.wasm` / `am.ik.wit`): language-independent -- no
rontolisp package, no external dependency. Direction: `eval -> am.ik.gpu`, `codegen.jvm ->
am.ik.gpu`, `am.ik.gpu -> nothing`.

| class | what it owns |
|---|---|
| `am.ik.gpu.Gpu` | the whole public surface: `available`, `description`, the `worth*` predicates, the members, `written` / `materialize` / `lazyResults` |
| `am.ik.gpu.GpuDevice` | the sealed seam over the two backends: `supportsDouble`, `thresholds`, `lazyResultsPay`, the members |
| `am.ik.gpu.CudaGemm` / `CudaDriver` / `CuResult` | the CUDA half: probe, context and module lifetime, members, pinned bounce buffer; the FFM binding; the status table |
| `am.ik.gpu.MetalGemm` / `MetalDriver` | the Apple half: probe, MSL library, MPS, buffer pool, members; the binding, one handle per selector SHAPE |
| `am.ik.gpu.DeviceResidency` | weakly-keyed identity LRU from host array to device copy, dirty/clean state, flush and free queues, stub backings |
| `am.ik.rontolisp.FloatArrayAccessHook` | the interpreter's two seams: every packed-array store and every read of packed storage reports here first |
| `am.ik.rontolisp.eval.LinalgGpu` / `LinalgGpuKernels` | the interpreter's interceptor, and the ONE reference to `am.ik.gpu` from `eval` so `-Pweb` can cut it |
| `codegen.jvm.JvmGpuTemplate` / `JvmGpuRuntimeBuilder` / `JvmLinalgGpu` | the compiled call site's glue; the blob; which members the bridge claims |
| `src/main/resources/am/ik/gpu/gemm.cu` / `gemm.ptx` / `gemm.metal` | the kernels: CUDA source and its checked-in artifact, and the MSL, which IS the artifact |

## The API

```java
static boolean available()                       // does this machine have one
static String  description()                     // what was found, or why nothing was
static boolean worth(long n, long m, long p)              // is this product big enough to offer
static boolean worth(long batch, long n, long m, long p)  // ... is this STACK of them
static boolean worthMap(long n)                  // ... is this ELEMENT-WISE map
static boolean worthStrided(long n)              // ... is this BROADCAST or GATHER
static boolean worthFold(long n)                 // ... is this AXIS FOLD
static boolean worthMatvec(long rows, long cols) // ... is this GEMV (once its matrix is resident)
static void    useKernels(String ptx)            // for an embedder that has no resources
static void    useMetalKernels(String msl)       // ... the same, for the Apple half
static double[] multiply(a, oA, b, oB, n, m, p)  // and a float[] sibling; allocates
static boolean  multiply(a, oA, b, oB, out, oOut, n, m, p)              // allocates nothing
static boolean  multiply(a, oA, strideA, b, oB, strideB, out, oOut, batch, n, m, p)
static boolean  map(int op, a, oA, out, oOut, n)                        // MAP_* op codes
static boolean  bcast(int op, a, oA, sA, b, oB, sB, out, oOut, dims)    // BIN_* op codes
static boolean  gather(a, oA, sA, out, oOut, dims)
static boolean  fold(int op, a, oA, out, oOut, outer, len, inner)
static boolean  matvec(w, oW, x, oX, y, oY, rows, cols)
static boolean  rngFill(...)                     // the seeded generator's fill; no operand
static void     written(Object hostArray)        // ABOUT TO BE written in place: its device copy is stale
static void     materialize(Object hostArray)    // about to be READ on the host: a lazy result comes home
static boolean  resident(Object hostArray)       // does the device hold a copy of it
static void     lazyResults(boolean on)          // results stay on the device until materialized
static boolean  lazyResultsIfWorthwhile()        // ... only where the backend measured that it pays
// resident-operand only, declined at any size otherwise:
static boolean  zip(op, a, oA, b, oB, out, oOut, n)                     // equal-shape binary
static boolean  scale(op, a, oA, double s, boolean swap, out, oOut, n)  // array-with-scalar
static boolean  where(m, oM, sM, ms, x, ..., y, ..., out, oOut, dims)   // the three-way select
static boolean  adamStep(x, g, m, v, n, double[] rule)                  // Adam, IN PLACE
static boolean  copy(a, oA, sA, spanA, out, oOut, sOut, spanOut, dims)  // reshape / transpose /
slice / concatenate
static boolean  takeRows(...) / gather(...) / scatterRows(...)          // the index tier
static boolean  sumSquares(...)                                         // the clip norm
// the FUSED tier: one pass each where torch.lisp composed a chain of members
static boolean  gelu(a, oA, out, oOut, n) / geluGrad(g, oG, x, oX, old?, oOld, out, oOut, n)
static boolean  softmax(a, oA, out, oOut, rows, len) / softmaxGrad(g, oG, s, oS, out, oOut, rows, len)
static boolean  layerNorm(x, oX, out, oOut, rows, len, eps) / layerNormGrad(g, oG, x, oX, old?,
oOld, out, oOut, rows, len, eps)
static boolean  dropoutMask(out, oOut, n, p, span, s1, s2, s3)          // the inverted-dropout mask
```

Row-major `n x m` by `m x p`. Four load-bearing properties:

- **`worth` and the member re-ask the same question**: `worth` so a caller can refuse before it
  unwraps operands, the member so the check cannot be bypassed. **Every `worth*` is probe-free and
  answers with the POOLED CUDA constant on every machine** -- knowing the threshold in force needs
  the probe (`dlopen`, `cuInit`, retained context, PTX JIT). The cost is a band between the constant
  and a backend's higher threshold in which an interceptor derives strides or a permutation and the
  library declines anyway. A test pins 100k `worth` calls under 200 ms with no probe run.
- **The batched pair is one call plus a per-batch ELEMENT STRIDE on each operand** -- one launch for
  the stack. A stride may be 0 (a BROADCAST operand), and then only one slab is copied: the span a
  launch reads is `(batch - 1) * stride + n * m`. That is every `torch:linear` over a `(B T C)`.
- **A member is a PARAMETER, not an entry point**: `map` switches on sixteen `MAP_*` codes,
  `bcast`/`zip`/`scale` on eleven `BIN_*` codes, so one kernel per width however the set grows. An
  unnamed op code is a decline, and each kernel's `default` is the identity rather than a member, so
  a slipped mirror cannot silently answer some other function. `GpuDeclineTest` checks the mirrors
  against both kernel texts.
- **The offsets are mandatory.** The compiled backend keeps a `[rank, dim..., data...]` header inside
  the same array; the interpreter passes 0. The result carries no header, so the caller wraps it.

## The runtime requirement is the driver, and nothing else

`SymbolLookup.libraryLookup("libcuda.so.1", Arena.global())` plus a `downcallHandle` per entry point.
No JNI, no bundled shim, **no CUDA toolkit** (no `libnvrtc`, `libcudart`, `libcublas`).
`libcuda.so.1` ships with the driver, which is what makes this compatible with the
no-external-dependencies rule. Run-time NVRTC compilation was tried in the spike and must not return.

On Apple: `libobjc`, `Metal.framework` and `MetalPerformanceShaders.framework` are the OS, the MSL
compiler is the OS, and there is no Xcode. `MTLCreateSystemDefaultDevice` is the only C entry point;
everything else is `objc_msgSend`, which on arm64 must be CALLED through a prototype matching the
selector rather than as the variadic it is declared as -- **`MetalDriver` holds one handle per
selector SHAPE**. A selector taking an `MTLSize` by value needs the struct layout; sending it through
a `long` shape is an immediate SIGBUS, not a wrong answer.

## The kernels: PTX checked in, MSL compiled at run time

`gemm.cu` is the source, `gemm.ptx` what `nvcc` makes of it; both checked in under
`src/main/resources/am/ik/gpu/`, and `cuModuleLoadData` hands the PTX to the driver, which JIT-compiles
it for the card present. Regenerate the pair together (a DEVELOPER-only toolkit requirement):

```bash
nvcc -arch=compute_75 -fmad=false -ptx src/main/resources/am/ik/gpu/gemm.cu -o /tmp/gemm.ptx
sed -n '1,12p' src/main/resources/am/ik/gpu/gemm.cu > src/main/resources/am/ik/gpu/gemm.ptx
cat /tmp/gemm.ptx >> src/main/resources/am/ik/gpu/gemm.ptx
```

`nvcc` cannot prepend a header, so the first twelve lines of `gemm.cu` (`//` comments, and therefore
valid PTX) are copied onto the front: that is how the regeneration command travels with the artifact.
`GpuDeclineTest` asserts it is still there.

- **`compute_75` (Turing, 2018) is the floor because CUDA 13 refuses anything older.** A card below
  compute capability 7.5 declines at the probe with that as its reason.
- **The load needs no cache plumbing of ours**: 26 ms the first time a given PTX text is seen, 1.4 ms
  every run after (the driver's `~/.nv/ComputeCache`), so no `cuModuleLoadDataEx` options are passed.
  MSL is the same (~35 ms cold, 2-3 ms warm; `MTLCreateSystemDefaultDevice` at 12-15 ms is the real
  probe cost) and needs no generated sibling and no virtual architecture.
- **`MTLMathModeSafe` is set explicitly** (falling back to `setFastMathEnabled:NO` on an older
  `MTLCompileOptions`): the relaxed default flushes denormals and reassociates, and the strided tier
  claims BIT-IDENTITY with the scalar defun, which neither survives.
- **`Gpu.useKernels(String)` / `useMetalKernels(String)` supply the text for an embedder that carries
  the CLASSES but not the resources**, read by the probe ahead of the resource. Exactly one caller:
  the JVM backend. A call after the probe has run changes nothing and is not an error.

## The probe, and lifetimes

One probe per process, cached in `Gpu`'s static initializer, answering on every machine without
throwing. CUDA is tried first, Metal second -- no machine has both and each declines in a failed
library lookup on the other's platform; the order only decides which SENTENCE a machine with neither
gets. CUDA sequence: open the library; `cuInit` / `cuDeviceGetCount` / `cuDeviceGet`; compute
capability `>= 7.5` checked explicitly (so the reason is legible rather than a
`CUDA_ERROR_NO_BINARY_FOR_GPU` from the module load); `cuDevicePrimaryCtxRetain` + `cuCtxSetCurrent`;
the PTX resource, `cuModuleLoadData` and `cuModuleGetFunction` per kernel; one
`cuMemAllocAsync`/`cuMemFreeAsync` pair to learn whether this driver's pool serves per-call memory.
`description()` is the outcome either way.

- **Only the stream-ordered allocator is an OPTIONAL symbol**; everything else has been in the driver
  API since CUDA 4, so the binding declines whole rather than half-binding. `CudaDriver.open` /
  `MetalDriver.open` answer `null` only when the LIBRARY is absent and let a binding failure THROW,
  so the probe prints "the driver could not be bound: ..." rather than blaming the machine -- which
  matters because a missing native-image registration fails at BINDING time.
- **Retained once, for the process** (primary context + module); process exit releases them. Every
  partial failure in the probe unwinds what it acquired (`CudaGemm.unwind`).
- **Per call, device buffers are freed on every path** -- success, decline, failure -- in a `finally`.
  Two different tests: `aRunOfSuccessfulProductsFreesEveryBufferItAllocates` (1000 products that
  work) and `aDeclinedProductCostsTheDeviceNothing` (twelve that fail, the path the first never
  enters). The latter must ask `cuMemGetInfo`, since a refused allocation is the pool failing to grow
  and has no pool-local counter.
- **The five `...FreesEveryBufferItAllocates` leak tests measure the POOL, not the device**
  (`CudaGemm.poolBytesInUse`, `GpuTest.driftSample`). `CU_MEMPOOL_ATTR_USED_MEM_CURRENT` is scoped to
  the pool HANDLE this process created, so it is immune to a sibling process (measured: unrelated
  process touching 8 GB moved `cuMemGetInfo` 1.3 GB and the pool count not at all). `cuMemGetInfo`
  reports the whole DEVICE -- and on unified memory the HOST's free memory too -- and drifted
  1.78-1.85 GB against the old 1.5 GB `GpuTest.DRIFT_BOUND` in a full `./mvnw test`, close enough to
  real-leak sizes (as low as 1.2 GB) that widening would hide leaks. `GpuTest.driftBound` falls back
  to `cuMemGetInfo` and the loose bound only for a driver with no pool.
- **On Metal the leak question is "does the pool reach a steady state"**, asserted by
  `MetalGpuTest.aRunOfCallsSettlesTheBufferPoolRatherThanGrowingIt` over 400 products after warm-up.
- **Threads.** The driver API is thread-safe and every call owns its buffers, so concurrent members
  are correct without a lock; they serialize on the null stream anyway. `DeviceResidency` is NOT
  thread-safe and the device attempt runs on the calling thread, which is why `--parallel` sits
  strictly below the device decision. Open caveat: a copy issued while another thread's kernel is
  queued on the null stream waits for it, INSIDE the critical window. Per-thread streams are the fix;
  nothing in the feature is threaded today.

### A DECLINE MUST COST THE DEVICE NOTHING, and that takes three calls in order

A pooled allocation that FAILS still grows the pool as far as it can on the way to failing and hands
back no pointer, so there is nothing to free and the high-water mark survives for the process AND
against every other CUDA process on the card. Measured before it was handled: one declined 80 GB
product took a 128 GB device from 69 GB free to 1 GB, permanently, while returning `null` correctly.

1. **A pre-flight.** The buffers' total is checked against `cuMemGetInfo` less 64 MB of headroom
   before anything is allocated. It costs 6-13 us a call, so it is AMORTIZED: remembered, decremented
   by what was handed out, re-asked every 64 allocations or as soon as a request exceeds a quarter of
   the remembered figure (`CudaGemm.allocate`). An erring estimate errs towards REFUSING, and a
   request the stale figure lets through still lands on the trim.
2. **A trim after a failed allocation -- three calls, IN THIS ORDER, or it silently does nothing**:
   `release()` the buffers that DID allocate (a trim finds them in use otherwise -- measured, a
   declined product held 78 GB with the two swapped); `cuCtxSynchronize`, because `cuMemFreeAsync` is
   STREAM-ordered and the buffers are only QUEUED (measured, a trim before the sync returns
   `CUDA_SUCCESS` having freed nothing); `cuMemPoolTrimTo`.

With all three, twelve consecutive declined 80 GB products move free device memory by 0 MB.
`GpuTest.aDeclinedProductCostsTheDeviceNothing` asserts the MEMORY, not the return value.

**Per-call allocation is the floor** (the feasibility spike's "~16-18 us floor" excluded allocation;
a per-call intercept needs three buffers a call). On the GB10 a pooled `cuMemAllocAsync`/`Free` pair
is **0.7-2.3 us**, flat in the size, against **136-336 us** unpooled. So products allocate through
the driver's pool, fall back to `cuMemAlloc` only where the probe's trial failed, and the size
thresholds move with the floor when they do. **Metal has no such pool**, and the cost there is not
the allocate/release pair (1.2-7.7 us) but pages faulting in on first write (a fresh-buffer n=512
product is 506 us against 308 pooled). So that backend owns a size-classed pool (floor 4 KB, bounded
by a quarter of `recommendedMaxWorkingSetSize`) whose slabs are SCRATCH -- fully overwritten in,
fully read out -- which is what lets it need no invalidation rule. Residency is the exception.

## `Linker.Option.critical` takes heap segments here too -- with a different bound

`cuMemcpyHtoD` / `cuMemcpyDtoH` take `MemorySegment.ofArray(a).asSlice(...)` directly under
`critical(true)`, offset included. Staging in a per-call confined arena loses at every size and the
gap WIDENS (1.04x at n=8 to 3.09x at n=1024), because staging is a per-call native allocation of the
operand's size. **So the library never stages for the UPLOAD.** The download is staged, for the
fresh-page reason under residency.

A critical call does not transition the thread to native, so the VM cannot safepoint while it runs.
Two ways that window gets long, two rules, neither "stage it":

1. **The copy is bandwidth-bound**: a copy over `CRITICAL_CHUNK_BYTES = 1 << 26` (64 MB) is SPLIT
   into chunks. 64 MB is ~1.1 ms of copy here (16.9 us/MB); an extra downcall per chunk is nothing.
2. **A device-to-host copy on the null stream also WAITS for the kernel.** A critical `cuMemcpyDtoH`
   straight after a launch holds the thread off a safepoint for the kernel's whole runtime (36 ms at
   n=2048 f64, 283 ms at n=4096, against 548 us and 2.2 ms after an explicit wait). Chunking cannot
   help -- the wait lands on the first chunk -- so the kernel is awaited by a plain,
   thread-transitioning `cuCtxSynchronize` before the result comes back, whenever the launch is big
   enough to matter. **The threshold is per-device, because a flop count is not a duration**:
   `SYNC_FLOPS_PER_MULTIPROCESSOR = 1 << 22` times the SM count (the probe reads it). On 48 SMs that
   is 2^28 flops, ~0.6 ms. The comparison is `>=`, not `>`: n=m=p=512 at f64 lands exactly on 2^28
   and must be on the syncing side.

Every remaining critical path drains the launch queue first through `awaitQueued` (a plain,
safepoint-friendly downcall), so the critical window holds the copy alone. That is what lets the
post-launch wait be SKIPPED under lazy results (`CudaGemm.awaitLaunched`); the `queued` flag is
volatile and racy by design -- a race costs one extra or one late synchronize, never a wrong answer.
On Metal none of this applies: a member stages through `MemorySegment.copy` into a shared slab, so
there is no critical window, and every call is `commit` + `waitUntilCompleted`.

## Declining on error, and the sticky rule

`CuResult` is the full CUDA 13 table -- **101 statuses, diffed against `cuda.h` at
`CUDA_VERSION 13000`** -- with one derived property, `sticky()`. The human sentence comes from
`cuGetErrorString` and is not duplicated.

**Re-diff the table against the header when it is extended.** An invented constant is worse than a
missing one: an unknown code is treated as sticky and retires the feature, so a constant that does
not exist but IS in the table with a guessed `sticky` flag can leave a dead context paying full round
trips to fail. (The first draft carried `917` and `918`, in no CUDA 13 header; gone.)

- **Any non-zero status declines**, after freeing every buffer. `CUDA_ERROR_OUT_OF_MEMORY` is an
  ordinary decline: this call was too big, the next may fit.
- **A sticky status retires the feature for the process.** The seventeen marked statuses (launch
  failures, uncorrectable memory errors, a destroyed or deinitialized context, a driver mismatch)
  leave the context unusable; `CudaGemm` sets `usable = false` and `Gpu` answers "unavailable"
  without touching the driver again.
- **An unrecognised code is not an error of its own** -- a newer driver may return one. `CuResult.of`
  answers `null`, `describe` still produces a string, `isSticky` assumes the dangerous kind.
- **Metal has no such state.** A command buffer ending in any status but `Completed` is an ordinary
  per-call decline.

## Every threshold, and what fixed it

A threshold sits where the win is UNAMBIGUOUS, not where it first appears: a "win" that is really a
tie is the one way this flag can do harm. Every one was measured against the fastest CPU path the
machine has (`--simd` on the JVM class output, JIT-warm), never against a flop count, and re-derived
per backend -- a 16-18 us floor and a 77 us floor do not accept the same shapes.

| member | CUDA | Metal | what fixed it |
|---|---|---|---|
| product `n*m*p` | **2^17** (2^21 unpooled) | **2^22** | CPU crossover: n≈45 at f64, n≈51 at f32 on the GB10; on the M4 Max n=128 is a tie and n=192 is 4.4x |
| STACKED product `batch*n*m*p` | the same 2^17 | the same 2^22 | the total-work point is a single product's; the floor is paid once for the whole stack |
| element-wise map, elements | **2^14** (2^16 unpooled) | **2^17** | at the threshold every member taken is clearly ahead and every member refused clearly behind |
| broadcast / axes transpose, OUTPUT elements (`MIN_STRIDED_ELEMENTS`) | **2^15** (2^17 unpooled) | **2^18** | 1.2x at 16384 is inside the measurement; 2.1x at 32768 is not |
| axis fold, INPUT elements | **2^17**, and at least **256** output cells | **declined at every size** as a round trip | a fold with one output cell is a single-threaded device loop |
| axis fold over a RESIDENT operand | 32 cells (one warp) | `MIN_RESIDENT_ELEMENTS` | there the CPU alternative is not a free walk but a DOWNLOAD |
| GEMV (`vec:matvec`), `rows*cols` | **2^17** (2^20 unpooled) | **2^21** | plus the two-sight rule, which no size can answer |
| generator fill, elements | **2^13** | declined -- needs a `double` | 0.7-0.8x at 2^12, 1.6-1.8x at 2^13, 20-45x at 10^6 |
| the RESIDENT tier | any size | **2^14** elements | a launch with no copy |
| MPS instead of our kernel (`MPS_MIN_WORK`) | -- | 2^27 per matrix | 1.5x at n=512, 4.5x at n=2048; MPS carries ~35 us of object churn a call and loses below n≈448 |

**The element-wise rule: a member is worth a round trip when its scalar cost is a libm CALL, and not
when it is a machine instruction.** (`ElementwiseCrossover.java` over `elementwise-probe.cu`, which
carries the DECLINED candidates so the refusals stay re-derivable, against
`elementwise-baseline.lisp`.) At 1.5 M f64 elements `erf` is 124x, `tanh` 22.6x, `exp` 17x, `log`
13x, `sin` 9.3x -- and `sqrt` 1.4x, binary `add`/`mul` 1.15x. At f32 the device column is FLAT
(241-245 us for every member: only bandwidth is left) and the binary ops LOSE. At the threshold the
cheapest member taken (`sin`) is 2.6x ahead and `sqrt`/`add` are 3.7x/6x BEHIND. **A member that wins
by less than its own measurement error is not a member.**

Two traps in any such CPU column. **A CPU figure depends on which widths the PROCESS has run**, by
1.3-1.9x (a bimorphic call site: f64 `exp` measures 9200 us in a both-widths harness and 7300 alone)
-- never mix widths inside one row. And **the FIRST shape measured in a process pays ~500 us a call
for its first few thousand device calls** (the device call path being JIT-compiled; it survives
best-of-three), which is why every benchmark here runs a throwaway bench at each width first.

**The strided tier exists because the element-wise refusal was a refusal of a different call.** That
refusal was of `add`/`sub`/`mul`/`div` at EQUAL shapes, where `--simd` runs a lane loop. The same ops
in a real `softmax` or `layer-norm` are BROADCASTS -- `(4 256 256) - (4 256 1)` -- and
`.kb/linalg-simd.md` says the broadcast path is a SCALAR ODOMETER walk in every `--simd` backend, "no
lanes"; same for the axis folds and for `transpose` with an axes list. So that CPU column is 3-8x the
one the element-wise round measured, and the same `linalg:sub` is a device member against a
`(4 256 1)` operand and a decline against a `(4 256 384)` one. The equal-shape refusal re-measured
through this tier's own kernel reaches the same answer (112.3 us at f32 against 85.0 on the CPU).

**A DECLINED strided call must allocate nothing, and that is an ORDERING rule inside the interceptor.**
This tier sits on `linalg:add`/`sub`/`mul`/`div` -- call sites a program runs constantly and which
mostly decline -- so the size test comes FIRST over a bound that costs nothing (a broadcast output is
at least as big as either operand; a transpose's output is the operand's element count), ahead of the
broadcast-shape derivation and the permutation check, both of which allocate an `int[]`.

## The accept rules against the shapes the programs run

An accept rule and the shapes actually flowing through it had never been put side by side. Measured
with a counting hook on `LinalgGpu.define` -- the one place every interpreter offer passes through --
keyed by member NAME and by ARGUMENT SHAPES as the rule sees them (width, dims, and RESIDENCY read
before the kernel runs); per-step numbers are one run diffed against a shorter one. The hook is a
temporary edit, not in the tree. **The interpreter answers for both offer layers**: `eval/LinalgGpu`
and `codegen/jvm/JvmGpuTemplate` are two copies of one decision that `GpuOfferDifferentialTest` pins to
agree, and the SHAPES are the program's.

What the census found, per step: the chapter-2 Transformer (`transformer-book-shapes.lisp`) makes
~4,100 offers with 212 declines, 191 of them `%la-scaled-masked-softmax` and its grad refusing an
`f(64 1 19)` padding mask against an `f(64 19 19)` score on `suffixLength`; the chapter-3 GPT
(`gpt-book-shapes-fast.lisp`) makes 12, none over an operand bigger than 65536 elements, because its
`(1 256 256)` mask IS a suffix once the leading extent-1 axis is dropped -- and its 634
`%la-sum-squares`/`%la-scale` offers (`torch:clip-grad-norm`) are all accepted, every operand resident;
llama2 offers only `vec:matvec`, of which **1440 a run decline on SIZE** at `f(288 288)` = 82944 against
`MATVEC_POOLED_MIN_ELEMENTS` 2^17. Everything else declining is free by arithmetic: a handful of calls a
step over a few hundred to a few thousand elements. Note the GPT's mask is a `double[]` over a `float`
score, accepted only because the device contract takes a mask of either width -- **the only production
shape in this repository that exercises that clause.**

Three ceilings priced off that census:

- **llama2's `288x288` GEMV against the 2^17 threshold is worth nothing.** Lowering
  `MATVEC_POOLED_MIN_ELEMENTS` to 2^16 flips 1440 declines to 1392 resident accepts and costs 5,304
  extra round trips and 20 ms more device time a run for a wall inside the noise, because a decode loop
  reads `y` on the host immediately. **The threshold stays.**
- **Chapter 2's DOUBLE `pe` buffer: accepting it is a LOSS.** `transformer/utils.lisp` keeps the
  sinusoidal positional encoding at `double` on purpose (`chapter02/section3.lisp` asserts a 1e-6 bound
  `f32` cannot hold), and the mixed-width pair is declined by `--simd` and `--gpu` alike. Rewriting the
  two `:pe` buffers to single float removes the step's last two downloads (4.85 MB) and makes the step
  9-19% SLOWER in HOST time -- the accepted add leaves a 2.4 MB result on the device every step and the
  resident tier pays to hold it, while launches and kernel time stay flat to 1.5%. **The mixed-width
  decline is a saving. A copy count is not a cost until someone removes the copies and times it**
  (`.kb/measurement-probes.md` rule 2: price the CEILING before building).
- **A `-1` reshape extent: built.** Both offer layers used to refuse it (`LinalgSimd.shape` /
  `JvmGpuTemplate.shapeOf` answered `null` for a negative extent), which dragged a resident array home
  for the defun. Now `LinalgSimd.reshapeShape` (interpreter, shared by `--simd` and `--gpu`) and
  `JvmGpuTemplate.reshapeShapeOf` (compiled bridge) resolve one `-1` against the operand's element count
  -- `linalg::%la-infer-shape`'s rule, mirrored rather than shared since neither package may import the
  other -- and decline exactly where the defun signals (a second `-1`, a non-dividing product). Every
  other reader of `shape`/`shapeOf` (`gather-strided`'s `od`, `col2im`'s `dims`, `transpose`'s axis
  list, `dropout-mask`'s shape) is untouched, and `GpuOfferDifferentialTest`'s boundary table carries all
  four `-1` spellings. `examples/deep-learning-from-scratch/ch07/train-convnet.lisp` (im2col) is the
  program: -11.4% compiled, -37.4% interpreted, 34 fewer `cuMemcpyDtoH` and 44 fewer `cuMemcpyHtoD`.

### The device contract, read against the implementations

Every clause of `GpuDevice`'s javadoc an implementation could silently narrow was read against
`CudaGemm` and `MetalGemm`. **Neither narrows anything**; the sweep is closed on both backends with one
ever finding (`MetalGemm.whereF` taking `float` only, fixed). The clauses: `GpuDevice.where` /
`GpuDevice.whereF` mask is
`double[]` or `float[]` of either width or `null`, and any of `m`/`x`/`y` may be a scalar;
`softmax`/`softmaxF` and grads take a mask of either width or `null`, a non-array mask being an explicit
decline; `gemmT`/`gemmFT` may refuse a non-plain orientation (both take all four); `take` mode 0 is
take-rows and 1 is gather; `gemv` is offered only once the matrix has been seen twice unwritten
(`offeredBefore`; `Gpu.matvec`'s `offeredMatvec` runs its bounds BEFORE the size and residency
gate);
`sumSquares` answers `null` on a decline; the fused tier is present on CUDA (all eighteen); "no method
here throws and none signals" -- every kernel entry is `try { ... } catch (Throwable) { return false; }`.
Every shape-level accept rule lives in `Gpu`, above the device; `CudaGemm` adds only `usable` and
allocation failure.

**No PRODUCTION code does arithmetic on a threshold, and none may.** Metal's fold threshold is
`Long.MAX_VALUE` and `2 * Long.MAX_VALUE` wraps NEGATIVE (which is how `GpuOfferDifferentialTest` came
to run every operand at 1024 elements there). Every site that multiplies, adds to or roots a threshold
is in a TEST; `Gpu`'s own use is `>=` and nothing else. **A threshold is safe to compare against and
unsafe to compute from.** One robustness note: `CudaGemm.where` used to map a non-null mask that is
neither `float[]` nor `double[]` to `mkind` 0 (the SCALAR-mask path) and so would have computed rather
than declined, while `softmaxKernel` (and `MetalGemm.softmaxScaledMasked`) wrote the same test as an
explicit refusal -- two spellings of one
guard, one failing open. Both are now explicit refusals.

## Precision

Three different breaks with the scalar defun, not the same kind.

**The product FUSES.** `gemm.cu` keeps ONE accumulator per output cell and walks `k` ascending across
tiles and within each tile -- the scalar defun's order, NOT a reordering. What differs is that
`acc += As[ty][k] * Bs[k][tx]` compiles to `fma.rn.f64` / `fma.rn.f32`, rounding once where the defun
rounds twice: a few ulps, not `sqrt(n)`-ish growth. Measured over random zero-mean inputs as a
fraction of the largest cell of the f64 oracle: **3.4-5.6e-16 at f64 and 2.1-9.0e-7 at f32 from n=64
to 512** -- and the f32 column is where a CPU f32 accumulation lands, so **at f32 the divergence is
the WIDTH, not the GPU**. Over inputs exact at the operand width the results match EXACTLY (what the
exact-input tests assert); over inexact ones the pin is a relative tolerance. A tuned BLAS fuses too
and agrees BIT FOR BIT up to n=128, separating from n=192 where OpenBLAS blocks its `k` loop -- so
`theDeviceIsAskedAheadOfATunedBlas` uses n=192 and says why.

**The transcendentals have their own libm, and that break can be SEEN.** Two correct `erf`
implementations may differ in their last ulps; on top of that the device kernel evaluates AT the
operand width (`expf`) where every CPU kernel evaluates in double and narrows on the store (`emap`'s
rule). Evaluating in double on the device was refused: an f64 transcendental costs a consumer card
32-64x an f32 one. Worst per-element relative difference from the scalar defun
(`elementwise-precision.lisp`): **one to two ulps for eleven of the twelve** at CUDA `#d`
(2.0-3.6e-16), ~1.2-1.7e-7 at CUDA `#f`, 1.7-3.3e-7 at Metal `#f`, with 27-162 of 400 samples
differing at all. `erf` is the outlier at `#d` (1.0e-15, 4.5 ulps) and the reason is on OUR side --
the CPU oracle is `%la-erf-1`'s A&S 7.1.6 series, not a correctly-rounded `erf`. **The feared 4.87e-5
on `tanh` from the feasibility spike does not reproduce at either width on either backend and should
not be quoted again** (Metal needed two fixes before that was true; see its section). One divergence
is visible rather than last-ulp: `(linalg:erf #d(-0.0))` above the threshold prints `-0.0` on the
device and `0.0` elsewhere -- the signed-zero wart `.kb/linalg-simd.md` records for wasm's `erf`.

The pins are **1e-12 relative at `#d` and 1e-5 at `#f`** -- three to four orders above the
measurement: loose enough never to flap, tight enough that a fast-math build, a mis-numbered op code
or a lost `-arch` fails instantly.

**The strided, resident, index and copy tiers are BIT-IDENTICAL to the scalar defun.** Their kernels
read widened to double, compute in double and narrow only on the store (`%la-bcast-loop`'s and
`%la-fold-axis`'s rule) and hold no libm; a gather and a copy move values. On Metal the same claim is
EARNED by the software binary64 route, in that section. `sqrt`'s NaN is the one wrinkle: the device
signs it and `Math.sqrt` does not, so the kernel canonicalizes.

**The one member that CANNOT be bit-identical is the clip norm, and it says so.** `%la-sum-squares`
is a whole-array reduction: every other reduction keeps its caller's order by giving each output cell
one thread, and a whole-array sum has ONE cell. (Rejected: a fixed blocked order both sides use -- no
single order is good on both machines, and the defun is the cross-backend oracle; leaving it on the
host -- 278 MB a run of downloads.) The kernel folds a grid-strided slice per block in a `double`
accumulator, tree-adds within the block, and the host adds the partials in block order from the
caller's seed. Every term is rounded exactly where the defun rounds it (`__dmul_rn`, `__dadd_rn`);
only the ASSOCIATION differs, and a tree is the better approximation. The block count is
`min(1024, ceil(n / 256))`, a pure function of the length, so the answer is REPRODUCIBLE run to run.
**Contract line: under `--gpu`, `torch:clip-grad-norm`'s norm is within a few ulps of the norm every
other backend computes, and is not equal to it.** The pin asserts closeness and reproducibility.

### The check that replaced byte-identity

**`--gpu` is the first flag whose results a user should not expect to match the other backends
elementwise**, and the guide says so. Three checks replace byte-identity and are strictly stronger:

1. Byte-identity still holds and is asserted everywhere the device is not asked -- below each
   threshold, for the refused members at any size (over a million elements), and for an equal-shaped
   binary pair at any size. Those last two fail the moment someone widens the member set without
   measuring it. **And it holds where the device IS asked, for the whole strided tier**
   (`theStridedTierIsByteIdenticalWithTheFlagOnEveryMachine`).
2. Above the threshold the pin is the relative tolerance above, per element, asserted on EVERY
   machine -- on one without a device the difference is exactly zero, so one test carries both worlds.
3. **`CUDA_VISIBLE_DEVICES=` still makes every flagged run byte-identical to an unflagged one**, the
   check that the flag is doing nothing behind the scenes.

`--gpu` **stays out of `ci-spec.yaml`** and the scalar `linalg.lisp` defun remains the cross-backend
oracle, exactly as for `--blas`.

## Device residency: the arrays stay on the device

**Do not change any part of this without reading the two enumerations below**: they are what makes a
cache of device copies sound, and each has a pinning test on each backend.

**What it is.** `DeviceResidency` maps a host array -- the primitive `double[]`/`float[]`, by
IDENTITY, the one object both interceptors already unwrap -- to a device buffer holding a copy of its
elements, with the span it mirrors (`offset`, `bytes`; a different span is a miss). Every member
looks each operand up before it allocates (a hit is the launch pointer and no upload) and records
what it uploaded or produced. The handle CANNOT live in the array: on the JVM class output the array
IS a bare `float[]` with its header inside it. Identity is sound rather than merely likely because
`make-array :displaced-to` rejects a packed array outright, so no second object can write another's
storage.

**Buffers are freed at the two moments a stream-ordered free is safe to enqueue**: the start of a
call, before any operand is looked up, and the end, after the launch and the download. A free
enqueued BETWEEN an operand's lookup and its launch would be ordered ahead of the kernel that reads
it. `Gpu.written(host)` drops an entry and QUEUES its buffer without a driver call -- it runs on
whichever thread wrote the array and needs no context.

**The keys are WEAK.** Held strongly, every activation and gradient a training step allocates stayed
reachable from the cache: heap to 14 GB, the pool growing one cold allocation at a time, the step
2.3x slower. So the key is a `WeakReference` with an identity hash, a collected key turns up on a
`ReferenceQueue`, and the next drain frees its buffer. `LinkedHashMap` in access order over those keys
is the LRU; a lookup presents a transient `Lookup` whose `equals` matches by referent, so a lookup
allocates no reference.

**A device copy to or from a host page the GPU has never touched costs ~9 us per 4 KB**
(`FreshPageCost.java`). The GB10 answers `PAGEABLE_MEMORY_ACCESS_USES_HOST_PAGE_TABLES` 1: the copy
engine reaches pageable memory through the CPU's page tables and the first translation of each page is
the cost. What warms a page is a CPU copy INTO it -- not the JVM's zeroing, not a store per page. Two
consequences:

1. **Every DOWNLOAD is staged** through one pinned 16 MB bounce buffer (`cuMemHostAlloc` at probe
   time, so every leak test's baseline includes it; a non-critical `cuMemcpyDtoH` into it and
   `MemorySegment.copy` out, 16 MB at a time, under one lock). A result array is always fresh.
   **The UPLOAD stays direct and critical**: its source was just written by the CPU, hence warm.
2. **The eager budget is a CAP on the pool, not a share of the card.** At a quarter of free memory
   (~30 GB) nothing was evicted before the collector got to it, every allocation grew the pool (5 us
   a call instead of 1), and the run was SLOWER with half the uploads than with none. At 64 MB, 256 MB
   or 1 GB it was 5-10% faster than no residency and the three were within noise, so the budget is
   `min(free / 4, 1 GB)`, re-derived at every pre-flight refresh. **The cap keeps the driver's pool
   recycling its warm blocks; it is not a safety margin.**

**Residency may slow a call by one upload but must never turn it into a decline.** The pre-flight
evicts everything the call is not holding, trims the pool, and asks again before it would refuse.

### The two seams, and what must report through them

`written` and `materialize` are residency's CONTRACT on the caller. **Every in-place write to a
packed array's storage must come through `written` BEFORE it lands**, or the next call answers for
bytes the array no longer holds (and lazily, a dirty copy's download would clobber the store).
**Every host READ of packed storage must come through `materialize` first**, or it reads the zeros of
an array nobody filled. Both are cheap when they do not matter (a volatile read, then an identity
compare) and never run the probe.

**The interpreter has ONE seam of each.** `LispSingleFloatArray`/`LispDoubleFloatArray` call
`FloatArrayAccessHook.written` from `setElement` and `.read` from the records' `data()` accessor -- so
`aset`, `row-major-aset`, `fill`, `replace`, `aref`, the printer, `toGeneralArray`, `read-sequence`, a
record pattern and Java interop all pass through it. The hook is a static in the ROOT package and must
not name any accelerator: `-Pweb` substitutes `eval/LinalgGpu`, whose `install` points the hook at
`LinalgGpuKernels`. The one reader that must NOT go through it is the device interceptor itself, which
takes `storage()`.

**Three kinds of writer bypass the setter and report themselves**, each found the hard way: the
`--simd` in-place kernels (`%la-adam-step`, `%la-scatter-rows`, `%la-scale`, `%la-rng-fill`), `vec:`'s
whole `-into` family, and the bulk `%read-sequence-packed` behind `read-sequence` over a packed array
(it fills storage through a `FloatBuffer` view, so a grep for writes through `.data()` saw a READ, and
it is how every model weight arrives). One case looks like a writer and is not: `torch:set-data`
REBINDS a tensor's data field.

**The JVM class output has no seam and ENUMERATES instead**, through `_gpuWritten`/`_gpuMaterialize`
(each guarded by `if (_gpuInited != 0)`, which lets `_fvAset1` be emitted before the bridge class is
defined): `_fvAref1/2/N`, `_fvAset1/2/N`, `_fvToGeneral`/`_fvToGeneralPrint` (the printer, `equal`,
every coercion); every argument of every accelerated `linalg:` call site, right after the device
attempt and before any host rung; every argument of every `vec:` call site, the `-into` destination as
written; the typed loops at `hoistArrays`, once per array (they are loop-invariant);
`_readSeqPacked`/`_writeSeqPacked`; and every argument of a Java interop call. `_fvDims`/`_fvLength`/
`_fvElementType` read the header only, written at allocation and never stale.

**The one reader that cannot be enumerated** is `am.ik.rontolisp.runtime.RontoFloatArray`, the
`rontolisp:jvm-export` handle: a class OUTSIDE the generated program, so it adopts the generated class
and resolves that class's `_gpuMaterialize`/`_gpuWritten` reflectively
([jvm-export.md](jvm-export.md), "The packed float array"). It is also why the boundary
(`floatArrayResult`) does NOT
materialize when it hands a result over -- that would download a result the next call re-uploads.

**The pins**: `everyEnumeratedWriterInvalidatesTheResidentCopy` and
`everyEnumeratedReaderMaterializesTheDeviceResult`, on EACH interceptor. **These are the tests to
extend when a new in-place writer or a new raw reader of a packed array is added anywhere in the tree.**

### Lazy results, and the result that has no host array

`Gpu.lazyResults(true)` makes every member's `finish` skip the download: the result buffer becomes the
array's DIRTY copy, and an in-place member marks the buffer it wrote. A dirty copy comes home through
exactly one operation, `Gpu.materialize`. A clean copy stays resident for the next member, so a chain
`matmul -> div -> where -> softmax -> matmul` moves nothing over the link until something on the host
reads a link. Off by default, so the library's contract ("`out` is filled when the call returns")
holds for any other embedder; `Gpu.lazyResultsIfWorthwhile()` is what the interceptors call and asks
`GpuDevice.lazyResultsPay` (true on CUDA, true on Metal since the asynchronous build).

**The device never drops a dirty copy on its own.** Every path that lets an entry go -- LRU eviction,
the pre-flight's `evictAll`, a replacement at a different span -- turns a dirty one into a `Flush`
(host array held STRONGLY, the pointer, the span), and the owner downloads it IMMEDIATELY after the
call that produced it: between the drop and the download the array has no entry and a reader would see
nothing to materialize. The pointer is QUEUED rather than freed, because an eviction inside `stage`
runs BEFORE the launch that reads the buffer. The LRU evicts CLEAN copies first and a dirty one only
when no clean one is left. `lazyResults(false)` brings every dirty copy home first.

**The lazy budget is not the eager cap.** Keeping the 1 GB cap made the first lazy build SLOWER than
the eager one: the autograd graph keeps a step's activations reachable until its backward, so with
~400 MB of dirty results live the cap evicted them as fast as they were made and the step paid the
download AND the re-upload. Lazily the budget is everything the device has less an eighth
(`LAZY_HEADROOM_SHARE`, never below 512 MB), refreshed at every pre-flight. At the book's shapes a
quarter share flushed 45 GB of graph during backward; the headroom rule flushed nothing.

**A lazy result allocates no host array: it is a STUB.** The value the program holds is still the
array, but SHORT -- the header alone (`[rank, dim...]`, 3 floats for a matrix) on the JVM, an EMPTY
`float[0]`/`double[0]` on the interpreter, distinct per result. Every header-only reader
(`array-dimensions`, `length`, `array-rank`, `array-element-type`, the type predicates, the printer's
prefix) works on it unchanged, which is why it is a short array of the same type rather than a new
kind of object; and the stub is the IDENTITY residency is keyed on. The elements live on the device
while the entry is dirty and -- from the first host touch -- in a BACKING the library allocates
(`DeviceResidency.storageFor`: the full span, the stub's prefix copied in), held in a second
weak-keyed map for as long as the stub is reachable. Four rules keep it sound:

- **A stub is told from a full array STRUCTURALLY**: a result array exactly the prefix ahead of the
  result offset (`Gpu.fitsResult`: `length == offset`) is a stub; one long enough to hold the span is
  a full array; anything between is a caller's mistake and declines.
- **A stub offered as an OPERAND has the extent of the span it stands for** (`GpuDevice.extent`: its
  own length, or the end of its entry's span, or its backing's length, whichever is larger). Asking
  `a.length` made every stub operand and every stub result decline.
- **A stub is in one of three states and never a fourth** -- a dirty device copy and no backing; a
  copy and a backing; a backing alone. Every path that lets a dirty copy go flushes it into the
  backing first; a stub with neither is a broken invariant and `source` throws rather than uploading
  zeros.
- **The two seams ANSWER the array to use.** `materialize` and `written` return `Object`: the host
  array itself, or a stub's backing. On the interpreter `data()` answers what the hook answers, except
  that the in-place `--simd` kernels report `storage()`, the stub, not the array `data()` handed them
  (reporting the backing left the stub's entry clean and stale). On the JVM every enumerated site
  REBINDS its local to the answer, and `_fvLength` at rank 1 reads `d[1]` rather than `d.length - 2`.

**The unswap rule: a host rung that answers its argument answers the CALLER's object.** Under this
mode the argument a host rung was handed is the BACKING; let that escape and the program holds two
objects for one storage -- the stub in its variable, the backing in the result -- and a device member
offered the backing keys a second entry that a write through the stub never invalidates: a silent
stale read. So every call site that hands a backing to a host rung maps the answer back through
`_gpuUnswap(result, original, handed)`. The interpreter has no such problem (its value is the RECORD
and the backing never leaves `data()`). **The one hole, named rather than closed, is Java interop**:
Java is handed the backing because it reads the array raw, and Java may STORE it as well as answer it.

**The fast paths remember FOUR arrays, not one.** `materialize`/`written` are called once per element
from an `aref`/`aset` loop, so each short-circuits on "nothing dirty"/"nothing resident" (a volatile
read) and on "the array I answered for last time". One remembered array was not enough: a loop that
reads one array and writes another (`concatenate`'s defun, a typed `dotimes` over two) alternated and
took the monitor on every element. The read ring holds `(host, storage)` PAIRS as one immutable object
per slot, so a reader racing the writer never sees one host's storage under another's.

### The tiers that exist only over a resident operand

**Every one is offered ONLY over an operand the device already holds** (`Gpu.resident`, a lookup
without a hit count), declined otherwise at any size, so the refusals' measurements stand untouched.
All are bit-identical to the CPU kernels they replace.

| member | `linalg:` shape | kernel |
|---|---|---|
| `zip(op, a, b)` | `add` `sub` `mul` `div` `maximum` `minimum` and the five masks at an EQUAL shape | `zip_fXX`, `bin_op` in double |
| `scale(op, a, s, swap)` | the same eleven with a SCALAR on either side | `scal_fXX`, the scalar a double whatever the width |
| `map(MAP_SQRT .. MAP_SIGN)` | `sqrt` `abs` `negative` `sign` | the map kernel's cases 12..15 (`MAP_LIBM_OPS` = 12 is where the size threshold stops applying) |
| `where(m, x, y)` | `linalg:where`, hence `torch:masked-fill`; any operand a scalar | `where_fXX` over a 4-stride layout |
| `adamStep(x, g, m, v, rule)` | `%la-adam-step`, IN PLACE: x, m, v stay resident and dirty | `adam_fXX`, every step an `_rn` intrinsic so nothing contracts into an FMA |
| `copy(a, sa, out, so, dims)` | `linalg:reshape` (hence `expand-dims`, `squeeze`, `flatten`), the rank-2 `linalg:transpose`, `%la-gather-strided` (hence `slice`, `broadcast-to`), `concatenate`, `%la-scale` in place | `copy_fXX`: one source and one destination stride per axis, either sign |
| `takeRows` / `gather` / `scatterRows` | `take-rows`, `gather`, `%la-scatter-rows` | `take_fXX` (two modes), `scatter_fXX` |
| `sumSquares` | `%la-sum-squares` behind `torch:clip-grad-norm` | `sumsq_fXX`, the ONE member whose fold order is not the caller's |

The size-thresholded members also take a resident operand at ANY size (`worthOrResident`).

**`scatter_fXX` needed a design.** The CPU adds slab `i` of the gradient into slab `idx[i]` of the
table for `i` ASCENDING, and a token embedding's indices repeat, so the order IS the value and atomics
would lose it. The kernel keeps it without atomics with **one thread per DESTINATION cell, not per
source element**: `Gpu.scatterRows` counting-sorts the indices by destination (stably, so each group
is ascending) and hands the kernel `start[rows + 1]` followed by the grouped source slab numbers;
thread `(r, k)` walks its own group in the defun's order over a cell no other thread touches. It also
inverts the traffic: the destination is a FRESH zero table, so the device pays an upload of 0.2-0.4 MB
instead of a download of 1.9 MB, and the table stays resident for the clip and the Adam step.

### The GEMV, and the matrix that stays

`vec:matvec` is the first member outside `linalg:` (79 GEMVs a token for `stories15M`). It was declined
twice as memory-bound; both declines were right per CALL and wrong per TOKEN once the matrix stops
moving.

**The rule that decides the upload is not a size.** The first sight of any matrix declines and leaves
a MARK -- an entry with no buffer, counting for nothing in the budget, freeing nothing when dropped,
cleared by `written` exactly as a copy is; the second sight of the same span, unwritten, uploads it;
every later one is a hit. So weights are resident from their second token, and a matrix the program
REWRITES between calls (llama2's KV cache, a Jacobian recomputed per step) is "first sight" every time
and never pays the cold trip it would lose (0.87x at 384x384 f32 cold). Alternatives: a threshold high
enough for the cold trip to win (2^19-2^20, where llama2's matrices never reach the device) or a bet
that the first upload is repaid (true for every weight, false for a rewritten matrix in 2^17-2^19).
The mark costs one `LinkedHashMap` entry per distinct matrix offered.

**The accumulator is a double at both widths.** Against the defun's rule (a double sum narrowed on the
store) over 1024 rows of 768 inexact floats: the double kernel is bit-identical on **1024 of 1024**
rows, a float kernel on 268, the `--simd` lane kernel on 144. Arithmetic, not luck: the product of two
floats is exact in double, so only the ORDER of a double sum separates device from defun, and that
moves the narrowed float only within ~1e-16 of a rounding boundary. ~2 us on a small resident call, and
it is what lets llama2's story stay byte-identical with the flag on. Pinned as a relative tolerance
plus ">99% of rows identical", not byte-identity. (Metal reaches 1024 of 1024 with a compensated float
pair; see its section.)

**The seam is a CHAIN on both backends.** Interpreter: `LinalgGpu.installVec`, called from the VEC
library's lazy-load hook after `VecSimd.install`, `define`s `vec:matvec` over whatever is bound (lane
native or defun) with the same declined-input protocol (`MetalGemm.gemvF` on the Apple side), and
installs the write hook itself since a
program may never reach `linalg:`. JVM: `JvmExprCompiler` routes a `vec:matvec` call site to
`JvmSimdCompiler.compileGpuMatvec` whenever the GPU bridge was emitted -- with `--simd` or without --
emitting the device attempt over temps and on `null` the lane kernel or the spliced defun. Declined:
anything not a packed rank-2 matrix and a packed rank-1 vector of the same width and matching extent,
a mixed pair (which the defun COMPUTES and the lane kernel refuses -- both are the captured binding's),
and the first sight.

**The tier survives the Java boundary, measured.** 200 chained GEMVs over a resident 2048x2048 f32
matrix run at 0.070 ms/iteration through a `RontoFloatArray` handle against 0.070 for the same chain
inside Lisp, with **1 upload for the whole run** where a materializing boundary pays 200 and 0.098-0.117
ms/iteration. The read at the end brings the result home exactly once and answers the same library
built without `--gpu` bit for bit; a `set` through the handle invalidates the resident copy the way the
emitted `_gpuWritten` guard does. (`examples/jvm/bench/`, `./run.sh gpu`.)

### The collector, and the flags that do and do not help

**On CUDA the library ASKS for a collection.** Stubs made the collector stop running: a stub is twenty
bytes, the young generation that filled every eighty results now takes minutes, and dropped stubs kept
their 25-100 MB device buffers resident until the pool hit its budget, where the LRU evicted them by
DOWNLOADING into fresh backings -- the allocation the mode exists to avoid. So the LRU evicts CLEAN
copies on its own and, when only DIRTY ones are left, STOPS and sets `collectionWanted`; the owner runs
`System.gc()` (the JDK's own direct buffers are the precedent), drains what the collector released, and
only then evicts what is still over budget, as flushes. A collection is asked for at most once per
eighth of the budget PRODUCED since the last (`COLLECTION_SHARE`, floor 64 MB). **The control:
`-XX:+DisableExplicitGC` makes the book's-shape run 4.5x slower** with only 16 s of pauses in it.

**What a collection COSTS is the pages, not the collection.** A full collection is 50 ms under either
collector and total pause time is 3% of the run. A compacting full collection moves every live array to
a new address and hands the regrown heap fresh pages, and a device copy to/from an untouched page costs
~9 us per 4 KB. `-XX:+ExplicitGCInvokesConcurrent` -- which never compacts -- recovers all of it while
RAISING requests from 24 to 97 and LOWERING pause time. (Correction for the next reader: "almost
nothing is allocated per step" is true of the LIVE SET (143 MB), not of the allocation rate, ~1.9 GB a
step, all dying.)

**The rule: the heap's pages have to be ones the program recycles.** **Hand-size a young generation
only where the program FILLS it** (`-XX:+UseParallelGC -Xmn8g` where a step allocates gigabytes is the
fastest thing measured at the book's shapes); **otherwise leave the collector alone and add
`-XX:+ExplicitGCInvokesConcurrent` to a long run**. Two traps: `-Xmn` sized for the wrong shape is
worse than none (57% slower at the notebook's width), and **`-XX:+AlwaysPreTouch` under G1 is a
disaster** (4x) because G1 pretouches every heap expansion INSIDE the pause.

**On Apple silicon this does not transfer.** Eagerly the request is never made (`System.gc()` appears
ZERO times in every configuration at both shapes), so `-XX:+DisableExplicitGC` costs nothing and the
pages argument has nothing to act on -- the pool's slabs ARE host memory. Under the asynchronous lazy
build the request IS made (about twelve full collections a run, 7 ms each: the heap holds stubs and
backings, not activations) and the default collector answers it best or tied;
`-XX:+DisableExplicitGC` then costs 4-10% at the book's shapes, for CUDA's reason in miniature.
Parallel-GC rows are 0-15% slower. **On a Mac: set `-Xmx` and stop** -- and note `-Xmx` there decides
TWO things, the heap and the pool the device's results live in, which is sized off the working set
less the heap. Nothing in `DeviceResidency` is per-device; a collection policy would be a `GpuDevice`
question and there is none to decide.

## The CUDA backend

**Fifty-two entry points in `gemm.cu`** (eleven in `gemm.metal`, which has no f64 sibling, no
generator and no fused tier), each taking its member as an op-code PARAMETER: the products
(`gemm_f64/f32`, the batched pair, two register-tiled f32 siblings), `map_f64/f32`, the strided
`bcast_*`/`gather_*`/`fold_*`, `rng_fill_*`, `gemv_*`, the resident tier's
`zip_*`/`scal_*`/`where_*`/`adam_*`/`copy_*`/`take_*`/`scatter_*`/`sumsq_*`, and the fused tier's
`gelu_*`/`gelu_grad_*`/`softmax_*`/`softmax_grad_*`/`log_softmax_*`/`log_softmax_grad_*`/
`layer_norm_*`/`layer_norm_grad_*` (and the affine pair `layer_norm_affine_*`)/`dropout_mask_*`.
A `_*` family name here stands for its `_f32` and `_f64` instances (`softmax_f32`, `layer_norm_f64`,
...); Metal has the `_f32` half only, plus `copy_strided`. A batched kernel is six lines -- it offsets the
three pointers by `blockIdx.z` times the strides and calls the SAME `gemm<T>` device function -- which
is why a batched cell folds `k` bit-identically to an unbatched one. `batch == 1` still launches the
PLAIN kernel with the parameter block it always had.

**One stride per operand, not an offset table -- and the decline that buys.** The CPU kernel walks the
batch axes as a mixed-radix odometer; the device adds `blockIdx.z * stride`. They agree exactly when
every axis's stride is that one stride times the axis's weight in the counter: true for a contiguous
batch of any rank and for a wholly broadcast operand (stride 0), FALSE for a broadcast axis under a
non-broadcast one -- `(2 1 40 40) x (2 3 40 40)`, whose `a` offsets go `0,0,0,base,base,base`. The
interceptors derive the stride in O(rank) and answer -1 when no single stride reproduces the odometer;
-1 is a decline. An offset table would be general, one more allocation and copy per call, and would
make the common case pay for a shape no example has.

**The strided layout rides BY VALUE in the parameter block on both backends**, for different reasons.
On CUDA a broadcast needs `3 * rank` ints; in a small pooled buffer the 192-byte `cuMemcpyHtoD` is
synchronous, so it ordered behind every kernel queued on the null stream and each strided call was a
hidden `cuCtxSynchronize`. It is now a fixed `strided_meta` struct (`4 * Gpu.MAX_STRIDED_RANK` = 64
ints, unused tail zero, because `cuLaunchKernel` copies the declared parameter size). `take` and
`scatter` keep their index BUFFERS, since an index list has no fixed size.

### The register-tiled f32 GEMM

The 16x16 shared-memory tile moves one element of each operand through shared memory per multiply-add
(~2.3 TFLOP/s on the GB10 against an f32 peak near 23). `gemm_tiled<T, TM, TN>` is a `16*TM x 16*TN`
block tile, 16x16 threads, each thread owning a `TM x TN` patch at rows `ty + i*16` and columns
`tx + j*16` -- so a warp's global loads and stores are contiguous and its shared reads conflict-free
-- with operands staged through shared memory 16 deep in k. Two entry points,
`gemm_batched_f32_t4` (64x64) and `_t8` (128x128), both taking the batched parameter block at every
batch size including 1. **f32 ONLY**: at f64 the scarce double units pin every tile to the same speed
and the 8x8 tile spills registers and LOSES.

**The fold is the 16x16 kernel's, bit for bit, which makes the choice free.** Each cell accumulates k
ascending from +0 through one `fma.rn.f32` per term over K rounded up to 16 with zero padding -- what
`gemm<T>` compiles to -- and a padded term is `fma(0, 0, acc) = acc`. `gemm-tile-probe.cu` found zero
differing cells; `everySingleFloatProductKernelLandsOnTheSameFusedFold` pins it against `Math.fma` at
shapes reaching each tile with M, N and K off the tile and off 16. So `CudaGemm.tileF32` may choose by
SPEED alone.

**The rule, in SMs so a smaller card moves with it: the 128 tile when both output axes are at least
128 and the grid has at least `SMs / 2` blocks; else the 64 tile when both are at least 64 and the grid
has at least `SMs` blocks; else 16x16.** The 128 tile is 2.2-4x once its grid holds about half the SMs
and loses below that; the 64 tile is the middle rung and takes a batch of SHORT rows; the 16x16 kernel
keeps everything small and everything f64. The `#pragma unroll` the probe carried was dropped: ptxas
unrolls the k loop itself at the same speed, and the pragma was 60 KB of PTX.

### The launch pipeline, and what a step is bound by

The step is **device-bound** (0.77 s of which ~0.72 s device kernel time at the notebook's shapes).
Two hidden host-side serializers were found and removed: the post-launch `cuCtxSynchronize` (which
predated lazy results) and the strided layout copy above -- 817 syncs and 1056 `cuMemcpyHtoD` a step
down to **53 and 57**, the survivors genuine operand staging, each draining the queue through
`awaitQueued`. Launches are ~2.5 us of API each and overlapped, so **the gap to PyTorch is not
launches**: its "eager fp32" GEMMs are TF32 TENSOR CORES (a precision class we deliberately do not
use, ~2.5x of the gap) and the rest is PASS COUNT -- its element-wise ops are fused single kernels
where we paid one full memory pass per `linalg:` member. That is what the fused tier answers.

**The copy route was measured and KEPT as it is.** The GB10 answers
`CU_DEVICE_ATTRIBUTE_INTEGRATED` 1 and `PAGEABLE_MEMORY_ACCESS` 1, so a kernel can read host memory
directly; `ZeroCopyRoute.java` priced four alternatives. A kernel over host memory is 4x on every op
at 1 M elements and is UNREACHABLE anyway: it needs the array pinned for its whole run, and FFM's
`critical` pins for one downcall. The only safe zero-copy is through pinned host buffers, and Java's
`MemorySegment.copy` into them runs at 35-60 GB/s single-threaded -- slower than the driver's pageable
copy past 262144 elements, winning 17% at 65536. Neither pays for a pinned pool and its budget.
**Any future change to this route re-runs that table first.**

## The fused tier

The compositions a transformer step spent a third of its device time on -- the exact GELU, softmax,
log-softmax, layer-norm's normalization (and its affine), the scaled+masked softmax and the dropout
mask, forward and backward -- each as ONE kernel where `torch.lisp` launched a chain of `linalg:`
members, one memory pass per member. **What a fused kernel buys on CUDA is only the passes it
removes**; the launches were already pipelined.

**The contract is the chain's, rounding for rounding.** A `linalg:` member computes in double and
stores at the width, so a chain rounds at every member boundary; each fused kernel reproduces every one
of those roundings (the `(T)` casts in `gemm.cu`), keeps every axis fold ascending and sequential in a
double accumulator, and evaluates `exp`/`erf` at the width as `map_op` does. So a fused member lands on
the bits the chain of DEVICE members would have produced --
`GpuTest.theFusedTierLandsOnTheComposedDeviceChainsBitsAtBothWidths` runs every chain member by member
and fused and asserts equality -- and the libm-free three (softmax's adjoint, layer-norm and its
adjoint, the mask) land on the CPU defun's bits
(`theLibmFreeFusedMembersAreTheSequentialReferencesBits`, a sequential Java replay against
`GpuTest.layerNormGradReference`'s walk;
`LinalgGpuDeclineTest` on every machine). The GELU adjoint, with two libm calls and a cancelling sum,
measures 1.8e-12 relative at `#d` and is pinned at 1e-9.

**The members are the compositions, on every backend** (`.kb/torch.md` "The fused compositions" has
the tape-order argument): the seven internal `linalg` defuns (`.kb/linalg.md`) ARE the chains, so
nothing moved on any CPU path, and `torch:gelu`/`torch:layer-norm`/`torch:softmax`/`torch:dropout`
print the same bits as the compositions they replaced (`TorchGradcheck.FUSED_PROGRAM` on the three
test backends, `ci-spec.yaml`'s `torch-fused-compositions` on all four). The two adjoints that fold
onto an accumulated gradient take it as an operand (`OLD`, a null pointer when there is none) so the
kernel adds it exactly where the tape's `%t-accum` would.

**The offer rule is the chain's.** A fused member with a libm call (GELU, its adjoint, softmax) is
offered from the map threshold or over a resident operand; the libm-free ones from the fused threshold
or over a resident operand, with the fold's cell rule on the row count. The mask is offered exactly as
`rngFill`. An operand the rule is not about -- the attention mask, layer-norm's `(len)` weight and
bias -- is bounds-checked and staged and does not decide. `linalg:softmax`/`linalg:log-softmax` are
intercepted in their `:axis` form over the LAST axis only, and on the JVM that form is an EXTENDED
call shape (`LinalgKernelCallLayout`) the way `sum :axis` is.

Three things measured on the way, all still load-bearing:

- **A thread-per-row kernel over global memory LOSES to the chain it replaces.** Thirty-two threads
  reading thirty-two rows read addresses a row apart. The row kernels stream rows through a transposed
  shared-memory tile, thirty-two columns at a time (`gemm.cu`, "THE ROW KERNELS' LAYOUT"): the thread
  still owns its row (a sequential double fold has no lane-parallel form that keeps its bits) but every
  global access is a coalesced column read or write. `ROW_WARPS` (2) is the block -- two `32 x 33`
  double tiles per warp under the 48 KB static limit -- and `CudaGemm.ROW_BLOCK` launches at exactly
  `ROW_WARPS * 32` threads, since the kernel derives its rows from that. Softmax 0.79 -> 0.27 ms.
- **nvcc contracts `a * b + c` into an FMA wherever the operands are doubles**, and at f64 every `(T)`
  boundary is a no-op, so `acc += dev * dev` rounded once where the chain rounds twice (one ulp in a
  layer-norm row and a softmax adjoint). The `__dmul_rn` family never contracts but DOUBLED the fused
  kernels, because the intrinsics switch off everything else the compiler does with a double
  expression. **The file is compiled with `-fmad=false`** (the header's regeneration command carries
  it), and the two products that should fuse -- the 16x16 `gemm<T>` and the GEMV -- say `fma()`
  explicitly (the tiled GEMMs always did).
- **The generator's jump is two hops.** `rng_fill` took the block's part once per block (one thread,
  shared) and each thread its offset inside the block (twelve bits at most) instead of
  square-and-multiply over the full exponent. Exact integer arithmetic either way, so the sequence is
  byte-identical (`theGeneratorFillIsBitIdenticalToTheSequentialWalk...`); the mask kernel inherits it.

Per-call gains at the book's shapes: `linalg:softmax :axis -1` 2.5x, `torch:layer-norm` forward 2.5x
and forward+backward 3.0x, `torch:gelu` forward 4.6x and forward+backward 3.2x, dropout little (its
fill is compute-bound). A step went 356 -> 272 ms of kernel time in 3774 -> 2964 launches.

## The tape-side fusions this tier depends on

- **The transposed product.** `torch.lisp`'s matmul adjoints (`g . b^T`, `a^T . g`) used to reach the
  product through a TRANSPOSED COPY; at the book's shapes that was 53.5 ms a step of `gather_f32`.
  Now `gemm<T>` and `gemm_tiled<T, TM, TN>` take two flags, `ta`/`tb`: an operand so marked has its
  `M x K` (or `K x N`) matrix STORED `K x M` (or `N x K`) and the staging load indexes it that way.
  The TILE the fold reads is the same, so **the product is bit-identical to the plain product of the
  transposed copy** (`GpuTest.aTransposedOperandIsReadInPlaceAndFoldsOntoTheUntransposedProductAtBothWidths`
  asserts equality at both widths and shapes reaching all three tiles). The per-batch stride is the
  operand's OWN either way. The staging swaps its thread indices to keep loads coalesced (16x16 loads
  `As[tx][ty]`, shared tiles padded to `TILE + 1`; the register-tiled staging loops run `m` or `k`
  innermost). Padding the REGISTER tiles the same way was measured and LOST, so they stay unpadded.
  **The seam is two members, not a flag**: `linalg::%la-matmul-nd-ta` and `-tb`, each arity 2,
  intercepted where `%la-matmul-nd` is (`LinalgGpu`, `JvmGpuTemplate.gpuMatmulNdTa`/`gpuMatmulNdTb`)
  -- two members because a member with flag arguments would need a new extended call shape on the JVM
  backend. The portable defuns are the transpose and the product they name, so `--simd`, `--blas`,
  both WASM backends and the plain interpreter are untouched. Metal carries them on the same two flags
  and on MPS's `transposeLeft:`/`transposeRight:` above the MPS threshold.
- **The attention head's transpose is a VIEW the tape carries** (`.kb/torch.md`, "The transpose
  view"): `torch:transpose` of the last two axes returns a tensor whose data is a marker naming the
  source, `torch:matmul` reads the marker and calls `%la-matmul-nd-tb`/`-ta` over the source where it
  lies, and records the SOURCE as the parent -- `(a^T . g)^T` is `g^T . a`, the same products in the
  same `k` order, so the bits are the eager node's. Nothing in `am.ik.gpu` changed.
- **The attention scale and mask are views too** (`.kb/torch.md`, "The views": the tensor record's
  `:scale` and `:fill` kinds), and `torch:softmax` in its `:axis` form consumes the chain as ONE node,
  `linalg::%la-scaled-masked-softmax (x scale mask fill ax)` with its `%la-scaled-masked-softmax-grad`
  adjoint. On CUDA that is
  the `softmax_*`/`softmax_grad_*` pair with the scale and mask folded in: each cell read as
  `(T)(x / s)` (the exact-reciprocal multiply where `Gpu.scale` would use it) and then as `fill` under
  the mask, both roundings reproduced. The adjoint applies `where(mask, 0, ·)` then the scale in the
  store, the tape's order. **The mask must be a TRAILING block of the operand** (its dims, leading 1s
  dropped, a suffix of the operand's) and may be either width; any other mask or axis declines to the
  defun. **"Fusing costs the kernel nothing" is not a premise**: reading the mask inside the row kernel
  cost about what the `where` pass it replaced cost, and MORE in the adjoint, because the row kernels
  run one thread per row (16384 threads, a tenth of the card) so a load per cell is exposed latency.
  **The mask therefore reaches the row kernel PACKED, one bit a cell**, through a `pack_mask_*` launch
  the same call makes just before (8 us); with the mask a whole number of 32-aligned rows a lane loads
  ONE word for its row and the lanes exchange bits by shuffle (`simd_shuffle` on Metal). Two more
  kernel-comment facts: a
  `__shared__` tile per template instantiation cost the PLAIN softmax 30% (the tiles are declared once,
  in the dispatcher), and the forward's first pass writes the scaled, masked row into the result as
  scratch so the exp pass reads it back rather than paying the mask and divide twice. The plain pair
  (no scale, no mask) is the pre-fusion body verbatim.
- **Layer-norm's affine** (`* weight + bias`) is inside the pair as `%la-layer-norm-affine` /
  `-affine-grad` (`.kb/linalg.md`; tape side in `.kb/torch.md`). The adjoint writes **two results**,
  `dx` and `g * norm`: the row statistics it recomputes anyway ARE what `norm` is made of, so the
  second result costs its store, and the three separate `j` loops of the plain adjoint's last chunk
  pass collapse into one, which pays for it. The parameters cost the row kernel nothing (their column
  index is UNIFORM across the warp, a broadcast load out of a 1.5 KB vector). On the CPU the backward
  used to recompute `norm`; a `%la-layer-norm-grad` sibling answers `(dx norm)` from the pass it
  already makes. **Metal DECLINES both members** -- built and measured there, not kept (below).

### Members weighed and declined, with the numbers that say so

- **A division by a power of two is launched as the multiply.** A `div.rn.f64` is the one arithmetic
  operation this card is slow at (a `(64 256 256)` `#f` scale is 0.140 ms as a multiply and 0.195 as a
  divide). Dividing by a power of two is exactly multiplying by its reciprocal -- two correct roundings
  of the same real number, for every operand including subnormals, infinities and negative zeros -- so
  `Gpu.scale` rewrites `op == BIN_DIV` with an exact reciprocal into `BIN_MUL`, in the one place both
  backends pass through. **The reciprocal must be normal at BOTH widths** (`Gpu.exactReciprocal` /
  `Gpu.normalPowerOfTwo` /
  `Gpu.normalPowerOfTwo`), because a backend computing in `float` (Metal) would otherwise multiply by one
  that underflowed to zero there. Pins: `aDivisionByAPowerOfTwoIsTheExactReciprocalsMultiplyAtBothWidths`
  (equality with the CPU's own divide over every divisor), `GpuDeclineTest` on the predicate.
- **`gelu_grad`'s cost is not its two libm calls** -- it is a SECOND `div.rn.f64` by `sqrt 2`, and
  `sqrt 2` has no exact reciprocal so the rewrite above cannot apply. Saving `t4` from the forward
  removes the `erf` and adds a fourth array: the step would trade ~4.3 ms of backward for ~3.5 ms of
  forward and hold 600 MB more on the device. **Declined on the numbers.**
- **The last-axis fold's tiling: measured, and declined on the census.** `fold_f32` with `inner == 1`
  gives thread `i` row `i`, so lanes read a row apart: 97 GB/s over 3038-wide rows against 255 GB/s
  over 384-wide ones (where the block's working set stays in cache). A tiled `fold_rows_f32` written
  and probed is bit-equal to the plain kernel at every shape and 1.33-1.96x, **but what decides the win
  is the ROW COUNT, not the byte count** (one thread per row is all the parallelism either has, and the
  tiled one pays a barrier per 32-column chunk): at a fixed ~50 MB operand, 1.82x at 16384 rows down to
  0.99x at 4096 and 0.89x at 2048, so the threshold would be ~6000 output cells. **Not built, because
  nothing reaches it**: instrumented, the book's step makes 864 fold calls over two steps and **not one
  has `inner == 1`** -- every one is an axis-0 fold over the batch or the sequence, already coalesced --
  and six other `linalg`-heavy programs make zero device fold calls. The long last-axis folds the
  measurement came from are exactly the ones the fused softmax / log-softmax / layer-norm removed.
  **Build it when a workload folds a LAST axis of more than ~256 elements over more than ~6000 rows on
  the device**: a `fold_rows_f32`/`_f64` pair beside `fold_f32` (a SEPARATE entry point, not a template
  instantiation -- an instantiation carrying a `__shared__` tile costs the plain path 30%),
  `CudaGemm.fold` choosing on `inner == 1 && cells >= threshold` and launching at `ROW_BLOCK` exactly.
  Nothing about `GpuDevice` changes, so Metal is untouched either way.
- **The reduction adjoint's zeros upload is gone with no code change**: `torch::%t-grad-bcast` staged
  `(linalg:add (linalg:zeros-like x) gk)` 90 times a step, all of them layer-norm's reduction nodes,
  which the fused normalization removed. It now runs ONCE a step over a scalar gradient. Instrumented,
  **zero `-0.0` elements reach `%t-grad-bcast`** -- nor `%t-unbroadcast`'s number branch or
  `%t-grad-reshape`'s, the two scalar fills of the same shape, which are never reached at all -- so
  the normalization `.kb/torch.md` records stands
  untested by any program here and stays as it is; note `%la-layer-norm-grad` MIRRORS the old adjoint
  spelling on purpose (the broadcast onto zeros included), so changing the adjoint alone would break
  that member-for-member pin.

### The decline that was only a materialize

`JvmLinalgKernelCompiler` emits the `linalg:` call site as a chain -- device attempt, `--blas` rung,
`--simd` lane rung, scalar defun -- and used to emit `_gpuMaterialize` over EVERY argument between the
device attempt and the host rungs. That guard exists for the LANE and LIBRARY kernels, which read raw
storage past every access hook. **The defun does not need it**: it is compiled Lisp and reports its own
reads and writes through the same guards. A device-only member has no lane kernel at any flag, so for
`%la-scaled-masked-softmax` the chain was *device attempt -> materialize five arguments -> defun*, and
the materialize dragged home a score whose FIRST reader is `linalg:div` on the device, which staged it
straight back: 288 round trips a step at the book's chapter-2 shapes. **The guard is now emitted only
where a host KERNEL rung follows it** (`simd != null || (blas != null && !extendedCall)`); the write
report for an in-place member is emitted whatever follows, because that one is about correctness. A
`--gpu`-only build now carries no guard at any `linalg:` call site.

The fix lands exactly on the acceptance ceiling: 292 -> 4 downloads and 302 -> 14 uploads a step, wall
0.491 -> 0.416 s, which is the WIDEN-every-head arm's own column. Widening `suffixLength` on top of it
is worth 0.8%, inside noise -- **so the mask rule stays as it is**, and with it
`JvmGpuTemplate.softmaxMaskLength` and both backends' `mask[i % maskLen]` kernels, which a widened rule
would have had to grow a stride for.

The guard was the COMPILE PATH's alone, measured: the interpreter costs 4 downloads and 14 uploads a
step before the change as after. The two paths never disagreed about a RULE -- they differed in WHEN a
host read is answered: the interpreter materializes LAZILY at the read through the record's `data()`
accessor while its interceptor hands the device `storage()`, and the compile path had no such accessor
for a lane kernel taking a `double[]` straight, so it materialized EAGERLY in front of every host rung.
It is now the same rule on both. **Read a decline's price before reading its rule** -- three items now
where the rule looked wrong and the layer below it was where the money was.

## The Metal backend

The same feature with a different member set. The flag, the CLI, the interception layer, the decline
protocol and the tests are shared; what is NOT shared is the width, every threshold, and two tiers.

| | CUDA | Metal |
|---|---|---|
| widths | `#d` and `#f` | **`#f` only** -- MSL rejects `double` outright |
| rank-2 product | our tiled kernel | **MPS** above `2^27` per matrix, our tiled kernel below |
| stacked product | our batched kernel | our batched kernel |
| transposed stacked product | `ta`/`tb` on the same kernel | the same two flags, and MPS's `transposeLeft:`/`transposeRight:` above the MPS threshold |
| fused tier | nine members | **eight of the nine** -- the dropout mask stays declined, on the draw's arithmetic |
| element-wise tier | twelve members | the same twelve |
| broadcast + axes transpose | yes | yes |
| axis fold `:axis` | yes | **not as a round trip, measured**; over a resident operand only |
| generator fill | yes | no -- it needs a `double` |
| `vec:matvec` | from `2^17`, double accumulator | from `2^21`, **compensated float** accumulator |
| lazy results + resident tier | on (`lazyResultsPay`) | on since the command buffers went asynchronous |
| resident set | every operand and result | eagerly **the GEMV's matrix only**; lazily every operand and result |
| index tier + clip norm | yes | the copies over a resident operand; `takeRows`/`pick`/`scatterRows`/`sumSquares` NOT members (kernels never written) |
| per-call floor | 16-18 us | **77 us** per COMMAND BUFFER eagerly; **15-26 us a member** in a lazy chain |
| per-call memory | the driver's pool | **our own** size-classed pool |
| kernels | PTX generated at build time, checked in | MSL compiled at RUN time, from a string |

**The dispatch seam** is `GpuDevice`, a package-private sealed interface over `CudaGemm` and
`MetalGemm`; `Gpu` is unchanged above it. Three questions cross it: `supportsDouble()` (so a `#d`
operand is a decline rather than a slower path), `thresholds()` (a 16 us floor and a 77 us floor do not
accept the same shapes), and `lazyResultsPay()`.

**Single float, or nothing.** Every double-taking method answers `false` without touching the device.
The rule is about an operand that ENTERS ARITHMETIC; the one that does not is `where`'s mask, taken at
both widths. Two consequences: **the decline protocol is load-bearing in a way it is not on CUDA** --
`linalg`'s default width is double, so the flag is inert until a program reaches `#f` data, which
`torch:` does by default -- and **`GpuTest` no longer describes both backends**: it is gated on a
double-capable device and `MetalGpuTest` answers the same claims at `#f`.

**Every call pushes an autorelease pool.** A command buffer, an encoder and every `MPSMatrixDescriptor`
are autoreleased; `objc_autoreleasePoolPush`/`Pop` measures 0.0 us, and its absence would be a slow
leak rather than a failure.

**The rank-2 product goes through MPS and the STACK does not.** MPS is in the OS, there is no f64
regression to weigh, and the two routes -- `MPSMatrixMultiplication` and our tiled kernel -- agree
BIT FOR BIT, which is what lets the choice be invisible;
which runs is a pure size decision, `n*m*p >= 2^27` for ONE matrix. The stack stays on our kernel
whatever its size because of the ZERO STRIDE: a broadcast operand (the rank-2 weight under a `(B T C)`
activation, i.e. every `torch:linear`) passes a per-batch stride of 0, which a batched
`MPSMatrixDescriptor` cannot be handed. Above the threshold the stacked route encodes one slab per
encode into ONE command buffer, since Metal's floor is per command buffer rather than per dispatch.
Bit-identity also means `rowBytes` may be `columns * 4` rather than `rowBytesFromColumns:dataType:`,
which PADS and would not describe our contiguous row-major data.

**THE AXIS FOLD IS NOT A ROUND-TRIP MEMBER HERE, and either half of the reason would be enough.**
`%la-fold-axis` accumulates in `double` at BOTH widths, so a float accumulator could not be
bit-identical (over a 256-long axis the divergence would be ~1e-5 relative). And the amax/amin half,
which needs no accumulator and WOULD have been exact, does not pay: the CPU fold is 85 us over 262144
f32 elements and 410 over 1048576 against ~150 and ~380 here -- a tie at best, and a tie is a decline.
So the round-trip fold threshold is `Long.MAX_VALUE`, and `mean`/`var`/`std`/`softmax`/`log-softmax`
reach the device through their broadcast and element-wise links only. Over a RESIDENT operand the trip
is not paid and the alternative is bringing the operand home, so `fold_f32` exists there.

**A declined call costs a little more here than on CUDA, because `worth` answers with the CUDA
constants** -- between those and this backend's higher thresholds an interceptor derives strides or a
permutation (two `int[]`) and the library declines anyway (~40 us on an axes transpose at
`(4 256 192)`). Letting `worth` consult the threshold IN FORCE was rejected: it would make a
documented, deliberately probe-free predicate answer differently depending on whether something else
had touched the driver first, and `GpuDeclineTest` pins its answer against the constant on every
machine. **Revisit with a measurement, not with this paragraph.**

### Precision on this backend

**The strided tier's bit-identity is an ARGUMENT here rather than an inheritance.** MSL has no double,
so `gemm.metal` computes in `float`: `+`, `-` and `*` over two floats are EXACT in binary64, so
rounding the exact result once to float is exactly what compute-in-double-then-narrow produces; `/` is
innocuous double rounding at these widths (53 >= 2*24 + 2); the strict selects and the gather move
values.

**Where that argument does not reach, the shader runs IEEE binary64 in SOFTWARE.** A scalar that is
not a float (`(linalg:mul g 0.1d0)`), every step of the Adam update (its rule is ten doubles) and the
sum fold. A value is its bit pattern in a `ulong`, every operation unpacks to sign / exponent / 53-bit
significand, works in a 128-bit integer so every intermediate is exact or carries a sticky bit, and
packs through ONE rounding step (`f64_pack`: round to nearest even, subnormals, overflow to infinity)
shared by add / sub / mul / div / sqrt and the exact widening and narrowing of a float. Division is
restoring (55 quotient bits, remainder sticky), the square root digit-by-digit over a 128-bit radicand
(56 root bits), the product four 32-bit partial products. A hundred-odd instructions an element, which
a memory-bound launch over a resident operand does not notice. `GpuDeclineTest` asserts no code line of
the file says `double`; the emulation is spelled `f64`.

**This GPU flushes subnormal floats to zero in every float operation, `MTLMathModeSafe` or not.**
Measured: a subnormal through `x * 7.0f`, `x > 0.0f`, `fabs`, `sqrt` all answer as if `x` were zero,
and a product landing in the subnormal range is flushed. The CPU does neither, so every float kernel
guards it (`bin_op_exact`): an operand that is subnormal, or a result below `FLT_MIN`, is recomputed on
the binary64 route; `abs`/`negative`/`sign` are bit operations, the fold's amax/amin compare through an
order key the flush cannot touch, and `where`'s mask test is a bit test. Two compares an element.
**And `sqrt` needs `precise::sqrt`**: plain `sqrt` under the safe math mode is 1 ulp off in ~10% of
operands, where `precise::sqrt` is correctly rounded and therefore `Math.sqrt` narrowed.

**Two transcendentals were FIXED rather than tolerated.** `tanh` and `sinh` measured 1.8e-4 and 3.1e-4
-- MSL's own carry an absolute error floor of ~3.4e-8 near zero (an exp-based formula cancelling), so
the relative error grows without bound as x -> 0. Both are odd with an `x + O(x^3)` expansion, so
`gemm.metal` takes the Maclaurin series to `x^9` below |x| = 1/4 (exact to ~1e-11 there) and the
builtin above. The other ten needed nothing. **`erf` has no builtin at all**, so the shader runs
`%la-erf-1`'s OWN series at float width, which makes Metal's `erf` closer to the oracle than CUDA's.

Pins: `theStridedTierIsBitIdenticalToTheScalarOracle`, and
`theSoftwareBinary64RouteLandsOnJavasDoubleArithmeticBitForBit` -- the scalar forms over 2^18 bit
patterns (subnormals, specials, tiny and huge) and twenty-odd scalars from 1e-310 to
`Double.MAX_VALUE`, the Adam update over three steps, the equal-shape ops -- against Java's arithmetic.

### The fused tier on Metal

Eight of the nine, in MSL: the exact GELU and its adjoint, the last-axis softmax and its adjoint, the
last-axis log-softmax and its adjoint, layer-norm's normalization and its adjoint. The row kernels are
`gemm.cu`'s -- ONE THREAD per row, thirty-two rows streamed through a transposed threadgroup tile
thirty-two columns at a time, two SIMD groups to a threadgroup -- and what makes them possible is the
software binary64 the resident tier already needed: the sequential `double` fold has no float form that
keeps its bits, and `f64_add` over a widened float is the CPU's own accumulation.

**Every member boundary goes one of two ways, and which one is not a choice.** `gemm.cu` spells a
boundary `(T)((double) a op (double) b)`; with `T = float` and no `double`, a boundary whose operands
are both floats IS that rounding taken once (`bin_op_exact`, flush guard included), and a boundary
against a constant the float grid does not hold (`1/sqrt 2`, `2/sqrt pi`, layer-norm's `eps`) takes the
software route -- exactly what the chain's `scal_f32` takes for those scalars.
`MetalGpuTest.theFusedTierLandsOnTheComposedDeviceChainsBits` runs each chain member by member over
RESIDENT operands (here a per-row intermediate is below every size threshold, so residency is the only
way to get an all-device chain) and asserts EQUALITY.

Per call at the book's shapes the tier is 1.4-6.0x, and it took **a THIRD off the step** where CUDA's
took a quarter -- larger here because it removes four command buffers out of five as well as the
memory passes.

**A FUSED MEMBER CAN MOVE BITS THE CHAIN DID NOT, wherever the chain STRADDLED a threshold.** A chain
member under a size threshold runs on the CPU in Java's libm; a fused kernel runs every member on the
device in the shader's. Where the chain was entirely on one side fusion changes nothing; where it
straddled, the fused member carries a device `log`/`exp`/`erf` the chain took from the host. Measured
here it costs the log-softmax pair and nothing else -- six of the eight are byte-identical to the
unfused build; the straddling member is the chain's `(linalg:log (linalg:sum ... :keepdims t))` over a
`rows x 1` array, 16384 elements, under this backend's map threshold of 2^17 (on CUDA the same array
CLEARS the lower map threshold). Declining the pair would restore byte-identity and cost 104 of ~330 ms
a step; a program that needs the previous bits on Apple turns the flag off.

**The dropout mask is the ninth and stays declined, on the ARITHMETIC.** Wichmann-Hill's uniform is
three binary64 divisions and two additions an element, and a binary64 division here is the software
restoring divide, fifty-five bit-serial steps. That is also why `rngFillF` is not a member here; fusing
the comparison and the scale onto the draw does not change which half is expensive.
`MetalGpuTest.theDropoutMaskStaysDeclinedHere` pins both.

**The offer rule needed a threshold of its own.** `Gpu.offeredRows` took the FOLD threshold for the
libm-free members, and this backend's fold threshold is `Long.MAX_VALUE`, so layer-norm and its adjoint
could never have been offered. A fused row kernel does not replace one fold; it replaces a chain of
memory passes and command buffers. `GpuDevice.Thresholds` gained a `fused` field: CUDA passes its own
fold threshold, Metal passes `MIN_MAP_ELEMENTS`.

**The libm-free members hold against a SEQUENTIAL JAVA REPLAY here too**
(`MetalGpuTest.theLibmFreeFusedMembersAreTheSequentialReferencesBits`: layer-norm, its adjoint onto a
fresh and onto an accumulated gradient, the softmax adjoint). The doubt was raised on the premise that
the fused kernel reduces as a THREADGROUP TREE; that premise is false -- these kernels run one thread
per row and fold SEQUENTIALLY with `f64_add` in index order, which is `%la-fold-axis`'s own
accumulation, and `row_tile` is a TRANSPOSED LOAD for coalescing, not a reduction. The five members
carrying a libm call could only ever be held to a BOUND. **One thread per row is the same fact that
appears as a COST in the mask fusion** (too little parallelism to hide a per-cell load) **and as a
GUARANTEE here** (no reassociation to forgive); which you meet depends on the question.

### The map threshold at the straddling shape: the clock ramp

The map threshold was set against `sin` over a WHOLE array; what straddles it is a chain's per-row
intermediate, a `rows x 1` array. Measured (`MtlPerRowMap.java`, `log` over a freshly written f32
operand): at the book's 16384 elements the CPU is 62-66 us, the device 98-137 us back to back, and
**419-510 us behind the chain's own gap**. **The third column is the one the chain gets.** This backend
refuses the axis fold at every size, so the `sum` that writes the operand is a 28-30 ms CPU loop with
the GPU idle: **this GPU lowers its clocks after ~1 ms idle and the first command buffer after such a
gap costs ~0.5 ms more**. So the crossover is near 2^15 back to back and at 2^17..2^18 behind the gap,
which is where the threshold already is. **The straddle stays.** The generalization: a size threshold
measured back to back is measured in the wrong context for any member whose operand a REFUSED member
produced.

**This is a Metal finding, not a device finding.** The same gap sweep on CUDA (host spinning 0 / 0.5 /
1 / 2 / 4 / 8 / 32 ms before one launch, persistence mode on) is flat to within 1% -- a 16384x3038 fold
is 1.869 ms at 0 ms of gap and 1.867 at 32 -- so back to back IS the right context there. **A threshold
is measured in the wrong context whenever the backend's clocks depend on how long it has been idle** --
a measurement per backend, not a property of devices.

### Residency and the GEMV on this backend

**The accumulator: compensated, and on the defun's bits without a `double`.** A plain float sum lands
on 229 of 1024 rows. `gemv_f32` keeps its running sum as a float-float PAIR: the product's rounding
error recovered exactly with an fma (`p = a*b; pe = fma(a, b, -p)`), every addition a TwoSum whose
error term goes into the low half, and the SIMD-group fold the same pair-wise. The pair carries ~48
bits against a double's 53 and is bit-identical to the double-accumulated oracle on **1024 of 1024**
rows at no cost the memory-bound pass can see. `#pragma METAL fp contract(off)` is kept: it is what
makes the error-free transforms mean what they say.

**The threshold is `2^21` and the COLD trip never pays.** On unified memory an upload is a memcpy of
the very bytes the CPU kernel would have streamed, so "cold" loses everywhere -- the two-sight rule is
not a refinement here but the member. The "kernel only" column IS the ~77 us floor until the matrix is
several megabytes: 1024x1024 is a tie, 1448x1448 2.5x, 2048x2048 4.8x, 4096x4096 9.4x.

**The idle clock sets the ceiling on a decode loop.** With a CPU gap before every call the same
resident head goes 347 us at gap 0 to 792 at 2.5 ms and 973 at 10 ms. A decode loop is exactly that
shape, so llama2 decodes at the same speed here with the flag and without it. There is no public API to
hold the clocks up. The member is in because it pays back to back from `2^21` by 2.5-9x; the guide says
both.

**Residency: kept for ONE kind of array, eagerly.** The full CUDA design made the step slower at every
cap (1-5%): on unified memory the upload residency removes is a memcpy, while a slab held out of the
pool costs the pool a FRESH slab for the next call of that size class, which pays its first-touch page
faults. A cap small enough to be free would evict the one array residency is FOR. So one thing goes in
the cache: **the matrix of an accepted GEMV** -- re-read hundreds of times, written never. `x` and `y`
are scratch slabs. A release gives the slabs back to the POOL, not to the device.

**The Java boundary finds nothing to defeat here** (eagerly): the chain through the `RontoFloatArray`
handle, the same chain inside Lisp and a per-call materializing chain are one number
(0.127-0.149 ms/iteration) and all three upload the vector 200 times, because a result that came home
eagerly has to go back up whatever the boundary does. `toArray()` moves NOTHING. The half that does
bite is `set` INTO THE RESIDENT MATRIX, which invalidates its device copy through the same
`_gpuWritten` guard. A performance finding, not a correctness one ([jvm-export.md](jvm-export.md)).

**`MIN_RESIDENT_ELEMENTS` = 2^14**, LOWER than the crossover table says, because a declined member over
a resident operand costs a materialize, the CPU loop and the re-upload of its result, and a chain that
flips between the two pays both memcpys at every flip.

### Asynchronous command buffers

**The interceptors switch lazy results on here** (`MetalGemm.lazyResultsPay()` is `true`) because under
the mode a call no longer waits: the step at the book's shapes goes **4.80 -> 1.81 s**, the notebook's
width 0.083 -> 0.041, and the loss series prints the same four decimals at every step.

**The mechanism.** `MetalGemm.commit` is the end of every member's encoding. Eagerly it is
`commitAndWait` (the library's contract "`out` is filled when the call returns" cannot be met any other
way, and a failed buffer is an ordinary decline). Lazily it commits, RETAINS the command buffer past
the call's autorelease pool, gives it a sequence number, and returns. Every slab the call held carries
that number as its `fence`; the buffers in flight sit in one deque, oldest first. One queue executes in
order, so "every buffer numbered at or below `retired` has completed" is a scalar, and `settle(slab)`
-- wait for the slab's fence, retiring everything up to it -- is the only wait there is. It is taken at
exactly these host touches and nowhere else: `stage`'s upload into a slab from the free list (a slab a
dropped operand left, which a launch in flight may still read -- the ordering the residency design
exists to forbid), and every download (`materialize`, the drain's flushes, `lazyResults(false)`). A
slab taken as a RESULT needs no wait, since the device orders its own reuse; `enter()` polls the head
of the deque without blocking.

**Failure surfaces at the first host read, never as zeros.** A buffer ending in any status but
`Completed` is learned of after its call answered `true`; `retire` marks the slabs it WROTE lost, and
the results of every later buffer in flight that READ one of them (a chain over a lost result is lost
with it), while a slab the failed buffer only read is intact and a slab taken fresh from the pool is
clean. A lost result throws the `IllegalStateException` the mode reserves for a result the host has no
other copy of, at `materialize`; a flush of one records its storage and throws at the read instead, so
switching the mode off never throws. Metal gives a kernel no way to fail on purpose, so the pin
(`aFailedCommandBufferSurfacesAtTheFirstHostReadOfWhatItWrote`) injects the STATUS through a
package-private seam.

**The budget rules the first asynchronous build got wrong**, each measured:

1. **The pool must be sized WITHOUT the heap** -- on unified memory slabs and heap are one physical
   memory. The lazy pool budget is the working set less `Runtime.maxMemory()` less an eighth. On a Mac
   `-Xmx` sizes the pool as well as the heap, and the guide says so.
2. **The resident budget must be counted in the pool's units.** The cache counts the SPANS it mirrors,
   the pool the power-of-two CAPACITY of its slabs, up to twice the span; at seven eighths of the pool
   the LRU never fired before the pool filled, and what ran instead was the pool's own pressure path,
   which evicted EVERYTHING as flushes (1200-1500 downloads and 10-12 GB of fresh backings in one call,
   and `OutOfMemoryError` when the phase made it forty gigabytes). It is now HALF the pool's
   (`LAZY_RESIDENT_DIVISOR`); at that the LRU fires, the collector is asked, and no flush happens.
3. **The pool's pressure path evicts a slab's worth at a time** (`DeviceResidency.evictSome`: least
   recently used first, clean before dirty), not the whole cache. And `drop` allocates a dirty copy's
   backing BEFORE the entry is let go of, putting the entry back if the heap runs out: an
   `OutOfMemoryError` inside a member is caught as a decline, and a stub whose entry was gone and whose
   backing was never allocated read its header as its elements.

**What did not change**: the eager path (`commitAndWait`, the per-call decline, the pool settling) is
byte-for-byte what it was and every eager pin holds; the kernels are untouched, so the resident tier's
bits are.

### Three further Metal findings

- **The strided layout rides by `setBytes:length:atIndex:` here**, not in a pooled slab. What it cost
  was one pooled slab acquired and released per strided call (`MIN_SLAB_BYTES` is 4096, so a 96-to-256
  byte layout took a 4 KB slab), its binding, and a pooled buffer that a committed command buffer reads
  -- a slab a future per-slab fence would have to cover. Counted: 724 layout slabs of 4444 pool
  acquisitions a step at the book's shapes, 55 of 346 at the notebook's width; after, exactly 724 and
  55 fewer. A `constant int* meta [[buffer(N)]]` parameter takes either (the slab route was
  `uploadLayout` writing the ints into a slab's `contents()` and binding it with
  `setBuffer:offset:atIndex:`), and `Gpu.MAX_STRIDED_RANK`
  keeps the length under `setBytes`'s 4 KB limit (four vectors at rank 16 is 256 bytes; the packer
  guards it and throws into the member's own `catch`, an ordinary decline). **Worth no measurable
  time; the COUNT is why it was done**, and every output is byte-identical between the two builds.
- **The attention scale and mask fold is worth 15% of the step here**, six times what it took on CUDA,
  and not for the reason predicted (the two removed waits). `torch:subsequent-mask` is
  `(linalg:triu (linalg:ones ...))` and `linalg:ones` builds DOUBLE, so the causal mask was a `double[]`
  that `whereF` refused here: the chain's fill ran on the CPU over a MATERIALIZED score, 7.9 ms a call
  where the whole fused forward is 1.6. The scaled/masked pair is TWO NEW ENTRY POINTS
  (`softmax_sm_f32`, `softmax_grad_sm_f32`) rather than CUDA's one-kernel-with-a-dispatcher, precisely
  because a second MSL entry point has its own threadgroup allocation and cannot cost the PLAIN kernels
  occupancy, and their source is byte-for-byte the pre-fusion source so their bits cannot move. The
  mask reaches the row kernels PACKED for CUDA's reason; `pack_mask` and the row kernel ride ONE
  command buffer as two dispatches of one compute encoder, so the packing costs a launch and not a
  second wait. The scale's boundary is `scal_f32`'s both ways.
  `MetalGpuTest.theScaledAndMaskedSoftmaxLandsOnTheComposedDeviceChainsBits` runs three scales (a power
  of two, an exact divide, and `sqrt 2` which is not a float) at both mask widths, both fills and both
  of the packed mask's reads, against the chain, and asserts EQUALITY.
- **A `where` mask is a PREDICATE, not a number**, so the width rule does not apply to it.
  `MetalGemm.whereF` opened with `if (m instanceof double[]) return false;`, and `linalg:where`'s test
  is `(/= m 0)` -- an integer test on the raw word, which is why neither width needs the `fp64`
  arithmetic this backend does not have (the softmax family's `pack_mask` already read a `double[]`
  mask as two `uint`s a cell, low half first, for the same reason). `where_f32` now binds its own mask as
  `device const uint*` and tests inline, one word at f32 and two at f64, and the host stages and looks
  the mask up at ITS width (`Call.lookupBytes` / `stageMask`; CUDA's `mkind`/`mwidth` pair already
  did this). Values and result are still single,
  because they do enter arithmetic. It matters because every attention mask in the library arrives as a
  `double[]` (`torch:subsequent-mask` and `torch:padding-mask` are built out of `linalg:ones` and
  `linalg:equal`, which build at `linalg`'s default width whatever `torch:` runs at). What still
  reaches `whereF` after the softmax fold is a whole class: a CAUSAL mask is a trailing block of the
  score and is folded, a PADDING mask (`(batch 1 length)` over `(batch query key)`) is not, so every
  masked attention that is not causal falls back to `%la-scaled-masked-softmax`'s three members, of
  which this `where` is one (7.8 -> 0.7-1.5 ms a call). **Nothing else this backend refuses on width
  alone is reached by the reasoning**: every other double operand is arithmetic on the value itself,
  and the comparison members PRODUCE masks rather than consuming them.
- **Layer-norm's affine: built, measured, NOT kept.** The MSL pair was written and pinned bit-identical.
  Per call it is worth a quarter of the adjoint (the forward's two broadcast passes are ~0.5 ms, the
  adjoint's ~3 ms, and the adjoint's decline was expensive because `%la-layer-norm-affine-grad` is
  spelled over `%la-layer-norm-grad-norm`, which **has no interception of its own on either backend** --
  so the decline landed on that defun's twenty-odd `linalg:` members, among them the axis folds this
  backend refuses at every size). Thirteen layer-norms a step is 45-50 ms of member time -- and **the
  step does not see it** (1.680 s declined against 1.702 fused, a coin flip): under asynchronous command
  buffers the work removed overlaps host work that remains. **Reopen it when the step is DEVICE-bound at
  these shapes** (a larger batch or model, or after enough host members move); the per-call table IS the
  upper bound, so re-take the step, not the members. **The trap that nearly landed it**: the first step
  measurement said 9.2% because it was taken before the "stop materializing an argument no host kernel
  will read" fix was merged, which took the DECLINING build from 1.798 to 1.680 s on its own.
  **Re-take a step number after every merge that touches the path, not only after one that conflicts
  textually.**

**A decline's cost to the pool differs by backend, and both are ONE-SIDED assertions.** On Metal free
memory routinely GROWS across a declined call (a call ENTERS the pool before it declines, and entering
drains the slabs of every host array the collector has reached); a declined product whose operands FIT
is allocated before the encode discovers it cannot proceed, and the slabs go to the free lists -- a
TRANSIENT, not a leak, which is why
`theSameProductRepeatedIsTheSameAnswerAndADeclinedOneCostsThePoolNothing` uses n = 100000, the shape
whose cost is stably zero. On CUDA free memory grows too, by a different route:
`CudaGemm.allocate`'s give-back ladder collects, trims, re-asks and then evicts every resident copy the
call does not hold, gated on `residency.occupied()` (+284 MB across twelve declined products in class
order, +1.29 GB with a gigabyte resident, 0 with nothing resident). **A two-sided device-memory bound is
available only where the residency is empty at BOTH ends** -- which is why `GpuTest`'s other two
`cuMemGetInfo` assertions (in `theResidentSetIsBoundedByItsBudgetAndAReleaseGivesTheMemoryBack` and the
lazy-results eviction test) can be two-sided: each takes both endpoints straight after a
`releaseResident()`.

## The interception layer

The flag over the same `linalg:` seam `--simd` opened and `--blas` widened. Read `.kb/linalg-simd.md`
for the declined-input protocol (the null sentinel, the captured binding, `LispEvaluator.applyGlobal`)
and `.kb/linalg-blas.md` for the flag whose shape this copies verbatim.

| backend | interceptor | kernels |
|---|---|---|
| interpreter (`prog.lisp --gpu`, native binary included) | `eval/LinalgGpu` (re-`defineFunction`) | `eval/LinalgGpuKernels` -> `am.ik.gpu` |
| JVM (`-o Prog.class --gpu`) | `codegen/jvm/JvmLinalgKernelCompiler` (call site) | `JvmGpuTemplate` -> the EMBEDDED `am.ik.gpu` |
| wasm-GC / `--no-gc` | out of scope, no FFM -- a hard error | -- |

**A `.wasm` output REFUSES rather than ignores** (`RontoLispCli.compileRecorded`, beside the `--blas`
guard). **`--gpu` is value-less and `RontoLispCli.enableGpu` is `enableBlas` one layer up.**
**Nothing may ask `LinalgGpu.available()` on a path that did not pass the flag** -- it runs the probe
(a `dlopen`, a `cuInit`, a retained context and a PTX JIT, ~26 ms cold; or
`MTLCreateSystemDefaultDevice` plus an MSL compile). That is the one way this flag is not like
`--blas`, whose availability check is nearly free.

### The intercepted set

**Fifty-seven `linalg:` members and one outside it.** By round trip: `linalg:dot` over two packed
rank-2 operands of the same width (hence `matmul` at rank 2 and `solve` transitively); `%la-matmul-nd`
(the STACKED product behind `matmul` at rank >= 3) and its transposed siblings `%la-matmul-nd-ta` /
`-tb`; the twelve element-wise `exp` `log` `tanh` `sin` `cos` `tan` `asin` `acos` `atan` `sinh` `cosh`
`erf`; the STRIDED tier -- `add` `sub` `mul` `div` `maximum` `minimum` at a BROADCAST shape only,
`sum` `amax` `amin` in their `:axis` form only, `transpose` in its axes form only; and `%la-rng-fill`
(behind `rand`/`randn`/`uniform`, the only member with NO operand). Over a RESIDENT operand: the
resident, index and copy tiers above. The FUSED tier: `linalg:softmax` and `linalg:log-softmax` in
their `:axis` form over the last axis, and the internal members `torch.lisp` spells its compositions
through -- `%la-softmax-grad`, `linalg::%la-log-softmax-grad`, `%la-gelu`, `%la-gelu-grad`, `%la-layer-norm`,
`%la-layer-norm-grad`, `%la-layer-norm-affine`, `%la-layer-norm-affine-grad`, `%la-dropout-mask`,
`%la-scaled-masked-softmax` and its `-grad`. Outside `linalg:`: `vec:matvec`, installed by
`LinalgGpu.installVec` from the VEC library's own lazy-load hook, because the two libraries load
independently and a program may reach either first.

**Nothing else is `defineFunction`ed**, and that is an assertion: `#'linalg:outer`, `#'linalg:norm`,
`#'linalg:matmul` and nine more still print `#<lambda>` under the flag.

The set is narrower than `--blas`'s in one direction and wider in the other, both by measurement: the
gemv shapes are here only over a resident matrix where `--blas` takes them outright; the STACKED
product is here where `--blas` stopped at `dot`; the transcendentals are here and are not a product at
all; the strided tier is here at ONE call shape per member and declined at the others.

**The generator fill is the one member whose device result is byte-for-byte the CPU's at every size**,
and that is what let it in: the closed form `a^k s mod m` lets thread `i` jump to its own state by
square-and-multiply (exact integers), then draw exactly as the sequential walk does -- the same
divides, the same left-associated sum, the same frac-by-compares -- every arithmetic step an `_rn`
intrinsic so nvcc cannot contract `lo + span * u` into an FMA, and `Gpu.rngAdvance` advances the END
state on the host by the same closed form. `linalg:seed`'s promise made bit-identity the price of
admission.

### The chain order, and why the device goes on top

On the interpreter a chain is INSTALL ORDER, so where `LinalgGpu.install` sits in
`LispEvaluator.resolveFunction`'s lazy-load hook IS the decision. It goes LAST:

```
--gpu --blas --simd  ->  device -> library gemm -> lane kernel -> scalar linalg.lisp defun
```

and every prefix works the same way. Three reasons: **`worth()` is probe-free and three orders of
magnitude above `--blas`'s** (2^17 against 64), so the device turns down everything small before
anything touches the driver -- underneath `--blas` it would never SEE a product, since the library
accepts from 4x4x4 up; **where it accepts it is at worst level with a threaded CPU BLAS and clearly
ahead at f32**; and **a declined member lands on the best CPU path the invocation asked for**, never
back on the scalar defun. The last is pinned with `.kb/linalg-simd.md`'s f32 v.M probe, whose two
spellings make the fallback target legible from Lisp (the defun prints 16778240, the lane kernel
16777216 or 16778176 depending on the machine and the spelling -- so the target is READ from an
unflagged run rather than written down).

**The wart, measured and accepted:** at n=64-96 with `--gpu --blas` both on, the device accepts a
product a 20-core OpenBLAS would have finished sooner (139 us against 21 at n=64, f64). `worth()` is
calibrated against `--simd`, which is what a machine without a tuned library has, and it cannot be
calibrated against `--blas` without `am.ik.gpu` learning whether a CBLAS is loaded -- which would make
a language-independent library depend on one. **On Metal the same wart runs from the threshold to about
n=1500**, because Accelerate's f32 gemm holds 2.1 TFLOP/s from n=1024 (the CPU cluster's matrix
coprocessor) and pays no per-command-buffer floor.

**On the NATIVE BINARY the wart is much wider and the reason is not the interceptor's.** One n=512 f64
product: `--gpu` 18500 us against the JVM's 735; `--blas` 7800 against 1160. The BLAS half is
explicable (single-threaded there); the GPU half is 25-60x with no threading involved, so the FFM
downcall path in the image is the suspect and neither `am.ik.gpu` nor `eval/LinalgGpu` changes between
the two. **Nothing may quote a device figure from a native-image INTERPRETER run without measuring it
first**; the workaround is what the flag wants anyway -- compile the program, and the class the native
binary emits is byte-for-byte the class `java -jar` emits.

**`--parallel` sits strictly BELOW the device decision on both backends**: the device attempt runs on
the calling thread (so does `DeviceResidency`, which is not thread-safe), and only what it declines
reaches the row-parallel lane kernel.

### The call site

`JvmLinalgKernelCompiler.compile` emits up to THREE attempts, in the interpreter's install order:

```
_gpuInit(); _blasInit(); _simdInit();          // the bridges, before their methodrefs
a = <arg1>; b = <arg2>;                        // each argument form evaluated ONCE
r = RontoLispGpuBridge.gpuDot(a, b);   if (r != null) goto end;   // --gpu
r = RontoLispBlasBridge.blasDot(a, b); if (r != null) goto end;   // --blas
r = RontoLispSimdBridge.laDot(a, b);   if (r != null) goto end;   // --simd
r = linalg$colondot(a, b);                                        // the scalar defun
```

The temps are what make a chain of any length safe: every decline branch RE-READS them, and
recompiling the argument forms would repeat their side effects
(`anArgumentFormIsEvaluatedExactlyOnceEvenWhenTheKernelDeclines`, in all three suites).

- **The device attempt is per MEMBER, not one hardcoded method**: `JvmLinalgGpu.kernelKey` maps each
  member to its `ops` key, where the key IS the bridge method name, so no third table sits between them.
- **The extended (option-form) call sites carry a device rung too** -- the axis folds and the axes
  transpose are device members ONLY in that shape -- claimed when EITHER bridge has a kernel for it and
  emitted with the SAME `LinalgKernelCallLayout` the lane attempt uses. A call shape at which nothing
  would be attempted routes to `compileDefault` rather than emitting an empty chain, which is what a
  `--gpu`-only build reaching `(linalg:sum a)` does.
- **The emit gate (`usesGpu`) is `programUsesSymbol` over EVERY member**, not `--blas`'s gate on
  `dot` alone: a
  transformer reaches only the stacked member and the ufuncs. A program that reaches no member embeds
  nothing, and **`--gpu` must NOT drag in the `--simd` bridge** -- a class that did would need
  `java --add-modules jdk.incubator.vector` to run.

### `-Pweb`

`LinalgGpu.available` / `description` / `install` / `installVec` are the only entry points into
`LinalgGpuKernels`, which holds the only reference to `am.ik.gpu` from `eval`, so BOTH bindings drop
out behind the same four substitutions (`src/web/java/.../Target_LinalgGpu.java`). **A new public
method on `LinalgGpu` that touches the kernels would break it, and only the Pages workflow's Web Image
build would notice**; `./mvnw -Pweb compile` is the local check. `codegen.jvm`'s reference does not
matter: those classes are read as RESOURCES, never linked.

## The JVM backend: the whole library travels in the class

`--blas` embeds one flat template class that is a hand-kept COPY of its kernels; a GPU binding is ~1700
lines across several classes plus two kernel texts, and the parts a copy would fork are exactly the
parts that were expensive to get right (the decline that must cost the device nothing, the 101-entry
status table, the per-device safepoint threshold, the chunked critical copies). So
`JvmGpuRuntimeBuilder` generalizes the template mechanism from one class to a CLOSURE of them plus data
resources:

- every class file of `am.ik.gpu` is renamed by ONE prefix rule, `am/ik/gpu/` -> the generated
  program's own package plus `RontoLispGpu`, so `Gpu` becomes `RontoLispGpuGpu` and a nested class
  follows its outer without being named. `Lookup.defineClass(byte[])` requires the defined class to
  share the lookup class's package.
- `JvmGpuTemplate` (the call site's glue) is renamed to `RontoLispGpuBridge` by the same pass, which
  lets it be WRITTEN against `am.ik.gpu` and type-checked by javac while resolving to the embedded
  copies at run time.
- each is base64'd into its own chunked string constant and `_gpuInit` runs one `defineClass` per blob.
  Definition order is free: a class file's references to its siblings resolve lazily.

**BOTH kernel texts travel in every `--gpu` class whichever machine emitted it**, because the machine
that compiles is not the machine that runs. They cannot be resources on the other side -- renamed into
a program's own package there is no such resource -- so `_gpuInit` hands each to `Gpu.useKernels` /
`useMetalKernels` before anything can probe. Those two public methods are the entire cost this route
imposed on the language-independent library.

**The size objection does not survive measurement.** `--simd` against `--simd --gpu` on one tree:
**2,571,220 against 172,427 bytes, so `--gpu` adds 2,398,793**, dividing as `gemm.ptx` 1,885,029
(78.6%), the 22 `GPU_CLASSES` class files base64 382,968 (16.0%, 287,225 raw), `gemm.metal` 71,701
(3.0%), the bridge base64 56,532 (2.4%). **The blob is the PTX, and the PTX is the FUSED ROW FAMILY**:
`softmax`, `softmax_grad`, `log_softmax`, `layer_norm*` and `gelu*` are 20 of the module's 58 entries
and 1,548,866 bytes -- 82.2% of the PTX and 64.6% of everything `--gpu` adds, the four softmax entries
alone 663 KB. The transcendentals an earlier version of this paragraph blamed are not entries at all;
they are op codes inside `map`, and `map_f32` + `map_f64` are 62,525 bytes, 3.3% of the PTX. **If the
blob ever has to shrink it is a fused-row question and nothing else is worth opening.** Every one of
those kernels is there because a measurement put it there, and the objection is answered anyway by
the fact that a `--simd` class already embeds a 62 KB `JvmSimdVectorTemplate` for the same reason.

**Two routes were weighed and rejected**: a `--gpu`-only support jar makes `-o Prog.class --gpu`
non-standalone, a real departure since every other flag emits a class that runs with a bare
`java Prog`; and a thin template reaching `am.ik.gpu` reflectively when the jar happens to be on the
classpath is a SILENT degradation -- "no device" declining quietly is a property of the MACHINE, which
the flag reports on stderr, while "you forgot a jar" is a property of the INVOCATION.

**What it is NOT.** The renamed classes are defined into the emitted class's own loader, so two `--gpu`
classes loaded by ONE classloader would collide on `defineClass` -- the same property the `--simd` and
`--blas` bridges have, and the reason the compiled-backend tests give each program a fresh
`URLClassLoader`. Each such loader also probes and JIT-loads the module again.

**Splitting the fused-row family's `f64` half (783,248 bytes) was priced and declined.** Ceiling 1,
class-load -- the cost every carrier pays: two classes differing by 783,488 bytes of embedded string
constant, timed `java -cp classdir Prog` 40 runs alternated, are **61.9 ms against 59.9 ms, no
direction, both inside the same noise band. Ceiling 1 is zero** (agreeing with
`.kb/jvm-aot-cache.md`'s "there is no class-loading cost to remove"). Ceiling 2, the driver's
PTX-to-SASS JIT inside `cuModuleLoadData`, is real -- 2,360-2,494 ms against 1,397-1,455 ms with the
`_f64` entries removed, `CUDA_CACHE_DISABLE=1` -- but it is priced against the wrong population (only
CUDA machines that RUN `--gpu` code reach the probe at all; a machine with no NVIDIA driver fails at
`SymbolLookup.libraryLookup` first), and it is cache-amortized: a second load of identical bytes with
`~/.nv/ComputeCache` warm is 4-7 ms whichever file, and every `--gpu` class ships byte-identical PTX,
so the cache warms once per machine ever. Metal's half has the same STRUCTURE at two orders of
magnitude less: `newLibraryWithSource:options:error:` is 32 ms the first time a text is seen on a
machine and 2-3 ms in every later process. **On neither backend does the embedded text cost anything to
a process that does not run it, and on neither does it cost a running process more than once per
machine. Nothing was changed.**

### The offer is decided twice, and what pins the two

The library travels, but the DECISION to offer a shape to it does not: `eval/LinalgGpu` is what the
interpreter runs and `codegen/jvm/JvmGpuTemplate` is the copy the compiled program carries, and both
sit ABOVE `am.ik.gpu`, so neither backend can correct a disagreement. A shape one accepts and the other
declines is a program that runs `java -jar` and `-o out.class` down different paths with nothing
failing.

**The pin is `codegen/jvm/GpuOfferDifferentialTest`**, and deliberately not thirteen per-helper
assertions. The two files share thirteen predicates -- twelve under one name each (`batchStride`,
`bcast`, `bcastShape`, `bcastStrides`, `copyInto`, `foldAxis`, `map`, `resident`, `rowMajorStrides`,
`sameShape`, `scale`, `zip`) and one under TWO, `LinalgGpu.suffixLength` against
`JvmGpuTemplate.softmaxMaskLength`, which is why `grep -rn suffixLength` reads as if the mask rule were
written once. So the question is asked from OUTSIDE both paths: one set of operands, each path's own
call shape, and the two must agree on accept versus decline and, where they accept, answer the same
bits. The shapes are chosen at the accept BOUNDARY -- a mask that is a trailing suffix and one whose
middle axis is extent 1, an exactly-equal pair, a rank mismatch, a fold on the last axis and one that
is not, a resident operand and a fresh one, both widths, the four `-1` reshape spellings -- and a
census assertion fails the run if the table did not both accept and decline, which stops a machine that
turned everything down from agreeing vacuously.

**Only the member-SET half runs on a GPU-less machine.** That half is device-free: every name the
compile path claims (`JvmLinalgGpu.qualifiedMembers()`) is bound to a sentinel and handed to
`LinalgGpu.install`, which OVERRIDES what it accelerates -- a name still bound to the sentinel is one
the interpreter does not accelerate, and a name the interpreter accelerates that the compile path never
claimed makes `install` throw with the member in the message. The SHAPE half cannot be asked without a
device, structurally: on both paths a shape decline and a no-device decline are the same `null`, the
compiled bridge fuses `!Gpu.available()` into the same `||` as the shape tests, `Probe.DEVICE` is a
static final holder and `GpuDevice` is `sealed permits CudaGemm, MetalGemm`.

**Closing that gap was priced and DECLINED.** Size is not the axis (a say-yes-to-everything third
`GpuDevice` implementing all 71 methods with constant bodies is 5,326 bytes, 7,101 base64, 0.30% of
what `--gpu` already adds; doubling the bridge outright is 2.4%). What decided it: **a divergence
cannot MANIFEST without a device** (every entry point in `Gpu` is `device != null && ...`, so on a
GPU-less machine both paths run their scalar fallback whatever their predicates say -- the gate defers
detection to the first machine on which the defect is observable at all); **the population of commits
that could carry one undetected is empty** (of 23 commits touching either file, 18 touched both, and
every one is a `--gpu` topic commit landing measurements here, taken on a device; longest gap between
device sessions, 6 days, bounds the latency); **and a stand-in would pin LESS than the shape half pins
today** (7 of the 43 boundary cases are over a RESIDENT operand, including all four `-1` reshape cases
-- the one time this pin has caught anything -- and a device that answers `true` without touching
memory answers `resident(host) == false`, so all 7 would agree VACUOUSLY; keeping them alive means
modelling `DeviceResidency`, the lazy stubs and `written`/`materialize` in the stand-in). Hoisting each
bridge member's `!Gpu.available()` out to expose the shape decision separately fails on the axis the
test was designed for: it makes the test assert PREDICATE against PREDICATE instead of OFFER against
OFFER. `GpuOfferDifferentialTest`'s javadoc now says it does not run on CI and why, so a green CI run is
not read as covering the shape rule.

**The thirteen bodies, read against each other. No pair is a different predicate.** Four are
word-for-word once the two representations are allowed for (`bcastShape`, `bcastStrides`,
`rowMajorStrides`, `batchStride`), and so is the loop inside `suffixLength`/`softmaxMaskLength`. The
rest differ only because a `LispFloatArray` carries `dims()` and `storage()` where a compiled array
carries a `[rank, dim...]` header: `sameShape` compares `Arrays.equals` against rank-then-length-then-
each-dim; `resident` is one hop of indirection apart; `copyInto` passes `{0, totalSize}` where the
bridge passes `{1 + rank, length - 1 - rank}`, the same span offset by the header; and `map`, `bcast`,
`zip`, `scale`, `foldAxis` make the same decisions in the same order with three guards the bridge needs
and the interpreter does not (`rank < 1`, `count < 1`, a `total + 1 + rank` overflow bound, because a
header can describe a rank-0 or empty array). Those three can only bite over a rank-0 or zero-extent
operand, which no tier reaching them can make RESIDENT. Same for the one arithmetic near-miss:
`suffixLength` answers `0` only when a mask extent is 0 (the interpreter declines `< 1`, the bridge
`< 0`), and a zero extent in the mask forces the matching zero in the operand, which both paths decline
for having no rows before either sees the mask.

## Tests

| what | where |
|---|---|
| the library, needs a DOUBLE-capable (CUDA) device | `am/ik/gpu/GpuTest` |
| the library, needs a METAL device | `am/ik/gpu/MetalGpuTest` |
| the library, must hold on EVERY machine -- both kernel texts included | `am/ik/gpu/GpuDeclineTest` |
| the native-image downcall registration, both drivers, every machine | `am/ik/gpu/NativeImageForeignConfigTest` |
| the interpreter's interceptor, needs a device | `eval/LinalgGpuTest` |
| the interpreter's interceptor, every machine | `eval/LinalgGpuDeclineTest` |
| the compiled interceptor, both halves | `codegen/jvm/JvmLinalgGpuAccelCompilerTest` |
| the two paths' OFFER, differentially -- member set on every machine, shapes on a device | `codegen/jvm/GpuOfferDifferentialTest` |
| the flag is value-less, the REPL pair, the `.wasm` refusal | `cli/CliOptionsTest`, `cli/RontoLispCliTest` |

**The dead-flag guard is the load-bearing one**, as for `--blas`: every numeric assertion would pass on
the scalar defun, so `#'linalg:dot` printing `#<function LINALG:DOT>` under the flag and `#<lambda>`
without it is the assertion that fails when the flag is DEAD. One assertion per accelerated member,
plus the complementary list of members that must still be `#<lambda>`. On the compiled side the guard
is the bridge NAME in the class bytes (the renamed library classes are base64 and do not appear as
text; the kernel texts do, which pins that they travel).

Things to know before editing these:

- **`GpuDeclineTest` is the half a CI runner actually runs** -- every machine this project has is
  GPU-less -- so it must never regress. It pins that the probe answers without throwing, that every
  decline condition declines rather than throws, that the status table is total and only the
  context-destroying statuses are sticky, that the PTX is the artifact the loader expects with its
  regeneration command attached, that the checked-in MSL names its kernels and holds no `double`
  outside comments, that the op-code mirrors match on both sides, and -- the one that matters -- **that
  an op code the library does not name DECLINES rather than quietly computing something**.
- **That last test hands the library the REAL checked-in text and no test anywhere may hand it anything
  else**: the override is process-wide and read at probe time, so a placeholder would decide what the
  whole suite's device compiles, whichever class ran first.
- **`JvmGpuRuntimeBuilder.embeddedGpuClasses()` is pinned against the class files the build actually
  produced** -- the guard that a class added to `am.ik.gpu` is added to the list that travels, since
  nothing can enumerate a package from a classpath, let alone from inside a native image.
- **The interceptor suites derive their shapes and their width from the device in force**, through the
  test-scope `am.ik.gpu.GpuThresholds` shim: `SIDE` is the smallest accepted square (64 on CUDA, 208 on
  Metal), `MAP_N` twice the element threshold, `TYPE` is `single-float` where there is no double. A
  hard-coded 64 makes every accepted-product assertion vacuous on the second backend.
- **The tests that assert on device memory hold a `@ResourceLock`.** Five (`...FreesEveryBufferItAllocates`)
  ask the POOL; two (`theResidentSetIsBoundedByItsBudgetAndAReleaseGivesTheMemoryBack` and the
  lazy-results eviction test) still ask `cuMemGetInfo` against the 1.5 GB bound, because their "before"
  is measured right after a release rather than a warm-up call. Every leak run is sized so a real leak
  is 2-8x whichever bound it uses.
- **A test that asserts an exact `residentBytes()` must KEEP ITS ARRAYS REACHABLE, and a process-wide
  `dirtyCount()`/`backingCount()` diff around one call is not that call's own effect** -- both written
  up in `.kb/test-execution.md`. The per-handle `DeviceResidency.dirty(Object)`/`.backed(Object)`
  predicates (`GpuThresholds.isDirty`/`.isBacked` outside the package) exist for exactly that.
- **A test whose shape does not clear the threshold that gates the mechanism it asserts on runs
  nothing, and passes** (`.kb/test-execution.md`, "A test that never ran the mechanism it asserts on").
  The two sweeps below found DIFFERENT tests vacuous, because the thresholds differ, so neither answers
  for the other.
- **Exact-input operands must be exact IN THE FOLD too** -- a 64-long sum of products of 1..4096 is
  not, at f32, because the defun accumulates in f64 and no f32 kernel can follow
  (`.kb/linalg-simd.md`'s reduction contract).

The claims each suite states as assertions rather than trusting: a batch is bit-identical to the same
slabs run one at a time; every element-wise op against `java.lang.Math` over its own domain (which is
what catches a mis-numbered op code); each strided member against a Java oracle written longhand; the
generator fill against the sequential walk at both widths and all three rules, and `rngAdvance` against
a 100,000-step walk; the two residency enumerations; the resident and index tiers against the CPU
kernels' bits and their declines without a resident operand; the clip norm's CLOSENESS and
reproducibility; the strided tier's byte-identity on every machine; and, on the JVM,
`aLazyResultAllocatesNoHostArrayOnTheCompiledBackend`, which runs the class in a CHILD JVM with a
256 MB heap holding forty-eight 16 MB results -- which fits only if none has a host array -- and runs
the same program without the flag under the same heap to see it die of `OutOfMemoryError`.

### The vacuity sweeps, and the rules they left

Both backends were swept for tests whose shape does not clear the threshold that gates the mechanism
they assert on. They found DIFFERENT tests vacuous, because the thresholds differ, so neither answers
for the other. Every finding was established by MUTATION (put the old constant or the always-true
condition back and watch the value assertions still pass). The rules that came out:

- **`Gpu.worth(n, m, p)` applies `POOLED_MIN_WORK` (131072), not the `Probe.MIN_WORK` the offer applies
  (4194304 on the M4 Max)** -- so "the shape is above the threshold" can be checked, believed, and
  wrong. `Gpu.multiply` is the only authority and `GpuThresholds.minWork()` the number to size against.
- **Size an operand off the FINITE thresholds only.** `GpuOfferDifferentialTest`'s `BIG` was
  `2 * max(map, strided, fold, fused)` and Metal's `fold` is `Long.MAX_VALUE`, so `BIG` wrapped to `-2`
  and every operand became 1024 elements -- the test was RED on Metal and invisible because every CI
  runner is GPU-less.
  `JvmLinalgGpuAccelCompilerTest.theFusedTierRunsOnTheCompiledBackendAndLandsOnTheChainsBits`
  was the unfixed twin (rows off `foldMinElements()`, clamped to 256, under the fused threshold, so
  every `array-equal` printed `T` from the defun against itself). Fixing the latter exposed a real
  divergence: `linalg:log-softmax` against its chain answers NIL, because the chain ends in a `log` over
  the ROW SUMS which runs on the host while the fused kernel takes its log on the device -- that line is
  now a bound rather than bit-identity.
- **Size a decline enumeration off the threshold of the tier it enumerates.**
  `GpuTest.everyStridedDeclineConditionStillDeclinesWithADevicePresent`'s three FOLD conditions were
  sized off `stridedMinElements()` (65536) while the fold is gated by its own 131072 floor, so all three
  declined on SIZE and the test stayed green with `Gpu#offeredFold` forced true. Now
  `max(stridedMinElements(), foldMinElements())`. (The control: the same mutation turns
  `GpuDeclineTest`'s fixed-shape version RED -- the device-free suite was carrying the device-present
  pin.)
- **A `...StillDeclinesWithADevicePresent` test now opens by asserting the same shape ACCEPTED, over the
  baseline's OWN arrays** -- its own because an accepted call leaves its operand resident and a resident
  operand is offered whatever its size, so a baseline over the enumeration's arrays would change the
  very gate the declines run into. Six tests carry one (product, element-wise, strided, matvec -- where
  it takes two calls -- batched, fused).
- **A resident-only member cannot be pinned with a fresh operand.**
  `theClipNormFoldsInBlocksOnTheDeviceCloseToTheSequentialSumAndReproducibly` reached the device on
  NEITHER backend, because `Gpu.sumSquares` is resident-only and so is the scalar `Gpu.scale` that built
  its gradient; the gradient is now built through a broadcast add. The fused tier's ROW-COUNT floor
  (`Gpu#offeredRows`' `rows >= foldMinCells()`) was pinned NOWHERE -- deleting the clause left all 139
  tests of the four suites green -- and is now pinned with a total ABOVE the threshold laid out in too
  few rows (128 x 2048 against 256 x 1024), the 256-row form asserted ACCEPTED first.
- **`eval/LinalgGpuDeclineTest`'s shapes are fixed and must stay fixed**: it is the half a GPU-less
  runner executes, so it may not size itself off a machine's thresholds. Same for `GpuDeclineTest`,
  whose fixed shapes happen to make most of its enumerations free device-PRESENT pins on a device
  machine (its `n` is `mapMinElements() * 2` and its strided shape exactly 262144, which clears both
  Metal floors; its batched 2097152 does not, and `theFusedTierDeclinesRatherThanThrowsOnEveryMachine`'s
  8 x 16 = 128 is vacuous with hardware on every backend, while
  `theResidentTierAndTheLazyHooksDecline...` declines because nothing is resident, which is its claim).
  On the GB10 seven of its nine enumerations are free device-present pins:
  `everyDeclineConditionDeclinesRatherThanThrows`, `theDestinationTakingFormDeclinesOnTheSameConditions`,
  `everyElementWiseDeclineConditionDeclinesRatherThanThrows`,
  `everyBatchedDeclineConditionDeclinesRatherThanThrows`,
  `everyMatrixByVectorDeclineConditionDeclinesRatherThanThrows`,
  `everyGeneratorFillDeclineConditionDeclinesRatherThanThrows` and
  `GpuDeclineTest.everyStridedDeclineConditionDeclinesRatherThanThrows`.

**Coverage: a pin inside a device gate is covered on ONE backend, not "on machines that have that
device".** `GpuTest`'s 57 claims were counted against Metal (its four hard-coded product shapes --
`theMatrixProductMatchesTheScalarOracleOnExactInputs`,
`theSingleFloatProductMatchesTheScalarOracleOnExactInputs`,
`theStackedProductMatchesTheScalarOracleAtEveryBatchShape`,
`everyCombinationOfTheThreeFlagsRunsAnExactProgramToTheSameOutput`, in `eval/LinalgGpuTest`, are the
ones that declined there for size and width), each "not a member" established by CALLING
the member over a RESIDENT operand: **31 covered, 8 not applicable, 18 gaps**, closed by 16 new tests in
`MetalGpuTest` (54, from 38). Not applicable: the allocator-route pair (no allocator switch on unified
memory -- the two-routes claim is `bothProductRoutesComputeTheSameProduct`, MPS against the tiled
kernel), the multi-chunk critical copy (`CRITICAL_CHUNK_BYTES` is `CudaGemm`'s), the two generator-fill
claims, the `#d` GEMV, `everySingleFloatProductKernelLandsOnTheSameFusedFold` (no family of per-shape
kernels with an `fma` contract here), the index tier and the clip norm. The sharpest gap was
`aDivisionByAPowerOfTwoIsTheExactReciprocalsMultiply`, whose `normalPowerOfTwo` argument exists
precisely for "a backend that computes in `float` (Metal)" and was asserted only where it is not needed.

**The inventory, so it is not recomputed.** Covered under the SAME name in `MetalGpuTest`:
`anInexactProductAgreesWithTheScalarOracleToTheWidthsOwnTolerance`,
`everyOperandIncludingTheResultIsReadFromItsOwnOffset`, `aRectangularProductUsesAllThreeDimensions`,
`everyDeclineConditionStillDeclinesWithADevicePresent`, `everyElementWiseMemberComputesItsOwnFunction`,
`anElementWiseMapReadsAndWritesFromItsOwnOffset`,
`aMatrixByVectorProductIsTakenOnlyOnceItsMatrixHasBeenOfferedTwiceUnwritten`,
`everyMatrixByVectorOperandIncludingTheResultIsReadFromItsOwnOffset`,
`everyMatrixByVectorDeclineConditionStillDeclinesWithADevicePresent`,
`aTransposedOperandIsReadInPlaceAndFoldsOntoTheUntransposedProduct...`,
`aCollectedHostArrayTakesItsResidentCopyWithIt`, `aLazyResultStaysOnTheDeviceUntilTheHostFirstReadsIt`,
`aWriteToALazyResultBringsItHomeFirst`, `anEvictedOrReleasedLazyResultIsDownloadedNotDropped`,
`theResidentTierIsOfferedOnlyOverAResidentOperandAndLandsOnTheCpuKernelsBits`,
`theStridedCopyIsTheCopyMembersOverAResidentOperandAndAScaleRunsInPlace`. Covered under a DIFFERENT
name: `theCheckedInPtxLoadsAndTheKernelComputes` /
`theSingleFloatKernelComputesTheSameExactValues` ->
`theCheckedInMetalKernelsCompileAndTheProductComputes` +
`theCheckedInMetalSourceIsTheArtifactTheLoaderExpects`;
`aBroadcastBinaryOpMatchesTheScalarOdometerWalk` -> `theStridedTierIsBitIdenticalToTheScalarOracle`;
`aSingleFloatMatrixByVectorProductLandsOnTheDoubleAccumulatedOracle` -> `...WithoutADouble`;
`aRunOfElementWiseMapsFreesEveryBufferItAllocates`, `aRunOfStridedCallsFreesEveryBufferItAllocates`,
`aRunOfMatrixByVectorProductsFreesEveryBufferItAllocates` and
`aRunOfSuccessfulProductsFreesEveryBufferItAllocates` ->
`aRunOfCallsSettlesTheBufferPoolRatherThanGrowingIt` /
`aRunOfElementWiseAndStridedCallsSettlesThePoolRatherThanGrowingIt` / `...SettlesThePoolRatherThanGrowingIt`;
`aBatchedProductIsThePerBatchProductOfEachSlab` / `aBatchIsBitIdenticalToTheSameSlabsRunOneAtATime` ->
`aBatchIsTheSameSlabsRunOneAtATime` + `aBatchAboveTheMpsThresholdAddressesEachSlabByItsOwnOffset`;
`anOperandUploadedOrProducedByARecentCallIsNotUploadedAgain` ->
`eagerlyOnlyTheMatrixOfAnAcceptedGemvIsKeptResident` (the deliberate opposite rule) plus the lazy
chain-is-a-hit census; `aWrittenHostArrayIsUploadedAgainAndTheAnswerFollowsTheWrite` ->
`aWriteToALazyResultBringsItHomeFirst`; `theResidentSetIsBoundedByItsBudgetAndAReleaseGivesTheMemoryBack`
-> `...GivesTheSlabsBack`; `aDeviceMemberUpdatingAnArrayInPlaceLeavesItResidentAndAuthoritative` ->
the in-place scale in `theStridedCopyIsTheCopyMembers...`; `aDeclinedProductCostsTheDeviceNothing` /
`theSameProductRepeatedIsTheSameAnswer` ->
`theSameProductRepeatedIsTheSameAnswerAndADeclinedOneCostsThePoolNothing`;
`theFusedTierLandsOnTheComposedDeviceChainsBitsAtBothWidths` ->
`theFusedTierLandsOnTheComposedDeviceChainsBits` +
`theScaledAndMaskedSoftmaxLandsOnTheComposedDeviceChainsBits`. Gaps CLOSED by new tests of the same
name: `aStridedGatherIsThePermutedCopyAtRankThree` (the `(0 2 1)` walk every attention head asks for),
`anAxisFoldOverAResidentOperandIsTheDefunsOwnSequentialFoldAtEveryInnerStride` (for
`anAxisFoldIsTheDefunsOwnSequentialFold`),
`everyStridedOperandIncludingTheResultIsReadFromItsOwnOffset`,
`everyElementWiseDeclineConditionStillDeclinesWithADevicePresent` (which `GpuDeclineTest`'s ungated
version already carries here),
`aStubResultAllocatesNoHostArrayUntilTheHostFirstReadsIt`,
`aWriteThroughAStubLandsInItsBackingAndTheStubIsUploadedFromIt`,
`anEvictedReleasedOrEagerStubIsDownloadedIntoABackingNotLost`, `aCollectedStubTakesItsBackingWithIt`,
`aBatchedProductReadsEveryOperandFromItsOwnOffsetAndBroadcastsEitherSide` (which also covers the
LEFT-operand broadcast of `aBroadcastOperandIsAZeroStrideAndReadsTheSameSlabEveryBatch`),
`everyBatchedDeclineConditionStillDeclinesWithADevicePresent`,
`everyResidentTierDeclineConditionStillDeclinesWithADevicePresent`,
`everyFusedDeclineConditionStillDeclinesWithADevicePresent`,
`aDivisionByAPowerOfTwoIsTheExactReciprocalsMultiply`,
`theLibmFreeFusedMembersAreTheSequentialReferencesBits`,
`theIndexTierTheClipNormAndTheAffinePairAreNotMembersHereAndDeclineOverAResidentOperand` (standing in
for `theIndexTierIsOfferedOnlyOverAResidentOperandAndCopiesTheCpuKernelsBits` and
`theSumOfSquaresFoldsInBlocksAndIsReproducible...`, which are NOT applicable here). Not applicable:
`bothAllocatorRoutesComputeTheSameProduct` (no allocator switch on unified memory -- the two-routes
claim is `bothProductRoutesComputeTheSameProduct`), `anOperandTooBigForOneCriticalCopyIsSplitAndStillAgrees`,
`theGeneratorFillIsBitIdenticalToTheSequentialWalk...`, `aRunOfGeneratorFillsFreesEveryBufferItAllocates`,
`aDoubleMatrixByVectorProductAgreesWithTheOracleToAFewUlps`,
`everySingleFloatProductKernelLandsOnTheSameFusedFold`.
`theInterceptorsRequestLeavesResultsEagerHereAndAnEmbeddersDoesNot`
pins that `Gpu.lazyResults(true)` stays the unconditional request an embedder or a test makes, honoured
on both backends, whatever `lazyResultsIfWorthwhile()` answers.

**The correction this produced, twice mis-stated before**: `MetalGemm.take`, `takeF`, `scatter`,
`scatterF`, `sumSquares`, `sumSquaresF` and the layer-norm affine pair return `false`/`null`
unconditionally -- **the kernels were never written**, and lazy results were never what stood in the
way. Now an assertion rather than a reading of the source, in
`theIndexTierTheClipNormAndTheAffinePairAreNotMembersHereAndDeclineOverAResidentOperand` -- over a
RESIDENT operand specifically, because that is the only state separating "not a member here" from "not
resident yet", and because a round that writes the kernels has to come to that test.

**What was NOT swept**: the rest of `GpuTest`'s 57 tests for the vacuity hazard (only the decline tests
and the three `cuMemGetInfo` assertions were), and `codegen/jvm` and `eval`, which the Metal sweep
covered.
## Native image

Two build inputs, both in `src/main/resources/META-INF/native-image/am.ik.rontolisp/rontolisp/`:

- **`resource-config.json`**: `gemm.ptx` and `gemm.metal`, each TWICE -- conditional on the device
  class, and again conditional on `JvmGpuRuntimeBuilder`, because a binary that only ever COMPILES
  never makes `CudaGemm` reachable -- plus `JvmGpuTemplate.class` and `am/ik/gpu/.*\.class`, the CLASS
  FILES the compiler reads as resources to embed them.
- **`reachability-metadata.json`**: a `foreign.downcalls` entry per distinct SIGNATURE -- 45 across
  both drivers and `--blas`, including the two-`MTLSize`-by-value entry for
  `dispatchThreadgroups:threadsPerThreadgroup:`. **Without them the linker REFUSES the handle at
  BINDING time, not at call time**, and both drivers bind every entry point in their constructor -- so
  one missing shape fails the whole binding and the binary reports "no driver" ON A MACHINE WITH A
  WORKING GPU and runs unaccelerated. That is how one round shipped. Two things stand against it now:
  the drivers answer `null` only when the LIBRARY is absent and let a binding failure THROW; and
  `NativeImageForeignConfigTest` binds both drivers against a lookup that finds everything (no device
  needed) and asserts every shape they ask the linker for has an entry.

Generate them with the tracing agent over a program that opens the binding and runs a member, then fold
the result in; the agent traces `Linker.downcallHandle`, so merely constructing a driver registers
every shape. **The type names must be the agent's own** (`jlong`, `jint`, `jboolean`): the un-prefixed
aliases parse, but `boolean` does NOT, so one spelling throughout keeps a re-run's diff empty. **A
per-entry `"comment"` key is rejected by the schema**, which is why the signature-to-entry-point
mapping lives in the file's top-level `comment` array.

Verified: a `--no-fallback` image built with the real binary's flags loads the checked-in PTX, runs
both kernels, takes the multi-chunk copy route at n=3072 and prints exactly what the JVM prints. CUDA
does not re-enter the `VectorAPISupport` / `SharedArenaSupport` fight; nothing here needs
`Arena.ofShared`.

## What is deliberately NOT here

Each is a measured decline, and each needs this file's numbers before it is revisited.

- **No element-wise member whose scalar cost is one machine instruction, AS A ROUND TRIP** -- `sqrt`,
  `abs`, `negative`, `sign` and the binary `add`/`sub`/`mul`/`div` at an equal shape. They ARE members
  over a RESIDENT operand, which is not a reversal but the case the refusal's measurement never had: a
  launch with no copy. Re-run `ElementwiseCrossover.java` plus `elementwise-baseline.lisp` before
  offering any of them as a round trip again.
- **No axis fold on METAL as a round trip.** The amax/amin half is the one to revisit first if that
  backend's floor ever drops; the sum half cannot come back while `%la-fold-axis` accumulates in double.
- **No index tier, no clip norm and no fused layer-norm AFFINE on METAL** -- the kernels were never
  written for the first two; the affine pair was written, measured and not kept (above). Reopen the
  affine when the step is device-bound at these shapes.
- **Nothing of `vec:` but `vec:matvec`.** `vec:matvec-into` writes a CALLER's array, which the device
  would have to download into and the caller's next write invalidate; `vec:dot` is one reduction over
  two vectors the device would have to be handed, and loses to the lane kernel at every size. The first
  sight of a big matrix used ONCE is left on the table deliberately (16 MB cold would have won 2.7x): a
  program that runs one GEMV does not care.
- **No zero-copy route, and no staged UPLOAD** -- both measured above. Measure with FRESH arrays before
  touching either half again.
- **The per-call cost of an FFM downcall inside a native image is still unexplained**; the generic
  `MethodHandle` invoker under every downcall (the driver's handles are instance fields and therefore
  not constants to the JIT) is the suspect.
- **No per-device collection policy.** It becomes a `GpuDevice` question only if the two backends'
  collection requests ever want different answers.
