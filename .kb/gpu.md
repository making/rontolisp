# `--gpu`: a matrix product on the GPU, or a decline

Two layers. **`am.ik.gpu`** is a language-independent library that takes a member and
either runs it on a GPU or answers "no" -- CUDA through the driver API, Metal through
Objective-C, behind one sealed `GpuDevice` seam. **The interception layer** is the `--gpu`
flag over it, on the interpreter and on the JVM class output; a `.wasm` output refuses the
flag outright and always will.

Read `.kb/linalg-simd.md` first for the declined-input protocol this is shaped for, and
`.kb/linalg-blas.md` for the flag whose posture it copies: **recommended, never required;
a machine without the hardware runs the same programs to the same output.** What is
DIFFERENT about a GPU is the fixed cost of a round trip and the fact that the accelerator
is a separate machine with its own memory -- and, since residency, that the arrays stop
coming back.

The user-facing description is `doc/{en,ja}/guides/gpu-acceleration.md`; the end-to-end
numbers a reader wants (what a training step costs, what llama2 decodes at, what the flag
is worth per member) live there and in `examples/llm-from-scratch/README.md`, not here.
**This file is the invariants and the mechanics: what each constant is, what measurement
fixed it, and which test pins it.** Where a number appears here it is because something in
the code would be wrong without it.

**Every number is re-derivable.** The probes are `.todo/123-gpu-acceleration/*.java` over
the shared driver-only bindings (`CuLib.java` on the CUDA side, `Mtl*` on the Apple one)
plus the `*-baseline.lisp` files for the CPU columns; that directory's README says which
answers which. They load the kernels this library ships rather than compiling any, so they
run wherever the feature does. The two machines every figure below comes from: an **NVIDIA
GB10** (Grace Blackwell, `sm_121`, 48 SMs, unified addressing, driver 580 / CUDA 13,
aarch64) and an **Apple M4 Max** (40 GPU cores, unified memory, 107 GB working set, macOS
26.3.1); both on Oracle GraalVM 25. A different device changes every number; what should
survive is the SHAPE of each result.

The round-by-round record of how this was built -- twenty-odd measured rounds between
2026-08-20 and 2026-08-24, each with its own before/after tables -- is in the git history
and in `.todo/.history.md`, not here.

## The invariant

**`am.ik.gpu` never throws and never signals.** Every failure -- no driver, no device, an
old card, a shape it cannot launch, a member too small to be worth the trip, device memory
exhausted, any `CUresult`, a command buffer that did not complete, an operand at a width
this device has no type for, a JVM that forbids native access, a platform with neither
`libcuda.so.1` nor `Metal.framework` -- is the same answer: `null` or `false`, and the
caller runs whatever it would have run anyway.

**Three things are deliberately NOT declines.** A `null` operand array throws
`NullPointerException`: the package is `@NullMarked`, so a null there is a contract
violation rather than an input. The array-returning `multiply` overloads allocate the
result and so can throw `OutOfMemoryError`; the `out`-taking overloads, which is what an
interceptor calls, allocate nothing. And **`Gpu.materialize` cannot decline**: when the
host has no other copy of the bytes, a download the driver refuses is an
`IllegalStateException`, because silence there would be a wrong answer.

Package rule, per CLAUDE.md's rule for `am.ik.jvm` / `am.ik.wasm` / `am.ik.wit`:
**language-independent -- it imports no rontolisp package and no external dependency.**
The direction is `eval -> am.ik.gpu` and `codegen.jvm -> am.ik.gpu`, and
`am.ik.gpu -> nothing`.

| class | what it owns |
|---|---|
| `am.ik.gpu.Gpu` | the whole public surface: `available`, `description`, the `worth*` predicates, the members, `written` / `materialize` / `lazyResults` |
| `am.ik.gpu.GpuDevice` | the sealed seam over the two backends: `supportsDouble`, `thresholds`, `lazyResultsPay`, the members |
| `am.ik.gpu.CudaGemm` / `CudaDriver` / `CuResult` | the CUDA half: the probe, the context and module lifetime, the members, the pinned bounce buffer; the FFM binding; the status table |
| `am.ik.gpu.MetalGemm` / `MetalDriver` | the Apple half: the probe, the MSL library, MPS, the buffer pool, the members; the binding, one handle per selector SHAPE |
| `am.ik.gpu.DeviceResidency` | the weakly-keyed identity LRU from a host array to its device copy, the dirty/clean state, the flush and free queues, the stub backings |
| `am.ik.rontolisp.FloatArrayAccessHook` | the interpreter's two seams: every packed-array store and every read of packed storage reports here first |
| `am.ik.rontolisp.eval.LinalgGpu` / `LinalgGpuKernels` | the interpreter's interceptor, and the ONE reference to `am.ik.gpu` from `eval` so `-Pweb` can cut it |
| `am.ik.rontolisp.codegen.jvm.JvmGpuTemplate` / `JvmGpuRuntimeBuilder` / `JvmLinalgGpu` | the compiled call site's glue; the blob (`am.ik.gpu`'s class files + the kernel texts, renamed and embedded); which members the bridge claims |
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
static void     written(Object hostArray)        // ABOUT TO BE written in place: its device copy is stale (and comes home first if it was the only one)
static void     materialize(Object hostArray)    // about to be READ on the host: a lazy result comes home
static boolean  resident(Object hostArray)       // does the device hold a copy of it
static void     lazyResults(boolean on)          // results stay on the device until materialized
static boolean  lazyResultsIfWorthwhile()        // ... only where the backend measured that it pays
// resident-operand only, declined at any size otherwise:
static boolean  zip(op, a, oA, b, oB, out, oOut, n)                     // equal-shape binary
static boolean  scale(op, a, oA, double s, boolean swap, out, oOut, n)  // array-with-scalar
static boolean  where(m, oM, sM, ms, x, ..., y, ..., out, oOut, dims)   // the three-way select
static boolean  adamStep(x, g, m, v, n, double[] rule)                  // Adam, IN PLACE
static boolean  copy(a, oA, sA, spanA, out, oOut, sOut, spanOut, dims)  // reshape / transpose / slice / concatenate
static boolean  takeRows(...) / gather(...) / scatterRows(...)          // the index tier
static boolean  sumSquares(...)                                         // the clip norm
```

Row-major `n x m` by `m x p`. Four things about this surface are load-bearing:

**`worth` and the member re-ask the same question**, deliberately: `worth` so a caller can
refuse before it unwraps its operands, the member so the check cannot be bypassed. **Every
`worth*` is probe-free and answers with the POOLED CUDA constant on every machine** --
knowing the threshold in force requires the probe (a `dlopen`, a `cuInit`, a retained
context, a PTX JIT), and an interceptor asks `worth` on a path that may never touch the
device. The cost is a band between the constant and a backend's own higher threshold in
which an interceptor derives strides or a permutation and the library then declines
anyway; that band and the decision not to close it are in the Metal section. A test pins
that 100k `worth` calls stay under 200 ms with no probe run.

**The batched pair is one call plus a per-batch ELEMENT STRIDE on each operand** -- one
launch for the whole stack. A stride may be 0, which is what a BROADCAST operand passes,
and then only ONE slab of that operand is copied: the span a launch reads is
`(batch - 1) * stride + n * m`. That is the shape every `torch:linear` over a `(B T C)`
activation has.

**A member is a PARAMETER, not an entry point**: `map` switches on one of sixteen `MAP_*`
codes and `bcast`/`zip`/`scale` on one of eleven `BIN_*` codes, so one kernel per width
however the member set grows. An op code the library does not name is a decline, and each
kernel's own `default` is the identity rather than a member, so a mirror that ever slipped
cannot silently answer some other function. `GpuDeclineTest` checks the mirrors against
both kernel texts.

**The offsets are mandatory.** The compiled backend keeps a `[rank, dim..., data...]`
header inside the same array as the data, so an interceptor must be able to say where the
elements start; the interpreter passes 0. The result carries no header, so the caller
wraps it.

## The runtime requirement is the driver, and nothing else

`SymbolLookup.libraryLookup("libcuda.so.1", Arena.global())` plus a `downcallHandle` per
entry point. No JNI, no bundled shim, no Java library, and **no CUDA toolkit**: no
`libnvrtc`, no `libcudart`, no `libcublas`. `libcuda.so.1` ships with the NVIDIA driver, so
"has a working GPU" is the entire runtime requirement, and that is what makes this
compatible with the no-external-dependencies rule rather than a compromise on it. The
feasibility spike bound NVRTC to compile CUDA C at run time; this does not, and must not.

On Apple the answer is one step shorter: `libobjc`, `Metal.framework` and
`MetalPerformanceShaders.framework` are the OS, the MSL compiler is the OS, and there is
**no Xcode**. `MTLCreateSystemDefaultDevice` is the only C entry point; everything else is
`objc_msgSend`, which on arm64 must be CALLED through a prototype matching the selector
rather than as the variadic it is declared as -- so `MetalDriver` holds one handle per
SHAPE and a selector is never sent through the wrong one. A selector taking an `MTLSize`
by value needs the struct layout, and sending it through a `long` shape is an immediate
SIGBUS rather than a wrong answer.

## The kernels: PTX checked in, MSL compiled at run time

`gemm.cu` is the source, `gemm.ptx` what `nvcc` makes of it; both are checked in under
`src/main/resources/am/ik/gpu/`, and `cuModuleLoadData` hands the PTX to the driver, which
JIT-compiles it for whatever card is present. Regenerate the pair together, with a toolkit
installed -- a DEVELOPER requirement only:

```bash
nvcc -arch=compute_75 -ptx src/main/resources/am/ik/gpu/gemm.cu -o /tmp/gemm.ptx
sed -n '1,12p' src/main/resources/am/ik/gpu/gemm.cu > src/main/resources/am/ik/gpu/gemm.ptx
cat /tmp/gemm.ptx >> src/main/resources/am/ik/gpu/gemm.ptx
```

`nvcc` cannot prepend a header, so the first twelve lines of `gemm.cu` -- `//` comments,
and therefore valid PTX -- are copied onto the front of the generated text: that is how the
regeneration command travels with the artifact instead of only living here.
`GpuDeclineTest` asserts it is still there.

- **`compute_75` (Turing, 2018) is the floor because CUDA 13 refuses to target anything
  older**, not because we chose it. A card below compute capability 7.5 declines at the
  probe, with that as its reason.
- **The load needs no cache plumbing of ours**: 26 ms the first time a given PTX text is
  seen, **1.4 ms every run after**, because the driver keeps `~/.nv/ComputeCache` and the
  resource is a fixed text. So no `cuModuleLoadDataEx` options are passed. The MSL is the
  same story on the other side (~35 ms cold, 2-3 ms warm; `MTLCreateSystemDefaultDevice`
  at 12-15 ms is the real cost of that probe) -- and it needs no generated sibling checked
  in and nothing pinned to a virtual architecture, which is strictly better than the PTX
  story rather than merely equal to it.
- **`MTLMathModeSafe` is set explicitly**, falling back to `setFastMathEnabled:NO` on an
  older `MTLCompileOptions`. Not a preference: the relaxed default flushes denormals and
  reassociates, and the strided tier claims BIT-IDENTITY with the scalar defun, which
  neither survives.
- **`Gpu.useKernels(String)` / `useMetalKernels(String)` supply the text for an embedder
  that carries the CLASSES but not the resources**, read by the probe ahead of the
  resource. Exactly one caller: the JVM backend, whose emitted class renames these classes
  into its own package where a classpath resource of ours cannot follow. A call after the
  probe has run changes nothing and is not an error.

## The probe, and lifetimes

One probe per process, cached in `Gpu`'s static initializer, answering on every machine
without throwing. CUDA is tried first and Metal second -- not a preference: no machine has
both, and each declines in a failed library lookup on the other's platform, so the order
costs a `dlopen` that was going to fail anyway. What it decides is which SENTENCE a
machine with NEITHER gets, and there the platform picks. The CUDA sequence: open the
library; `cuInit` / `cuDeviceGetCount` / `cuDeviceGet`; compute capability `>= 7.5`,
checked explicitly so the reason is legible rather than a `CUDA_ERROR_NO_BINARY_FOR_GPU`
from the module load; `cuDevicePrimaryCtxRetain` + `cuCtxSetCurrent`; the PTX resource,
`cuModuleLoadData` and `cuModuleGetFunction` for every kernel; one
`cuMemAllocAsync`/`cuMemFreeAsync` pair, to find out whether this driver can serve
per-call memory from its pool. `description()` is the outcome either way.

**Only the stream-ordered allocator is an OPTIONAL symbol.** Everything else has been in
the driver API since CUDA 4, so a driver missing one of those is not a driver, and the
whole binding declines rather than half-binding. `CudaDriver.open` / `MetalDriver.open`
answer `null` only when the LIBRARY is absent and let a binding failure THROW, so the
probe prints "the driver could not be bound: ..." rather than blaming the machine -- which
matters because a missing native-image registration fails at BINDING time (below).

- **Retained once, for the process.** The primary context and the module are exactly what
  a per-call intercept must not pay for; the process exit releases them. **Every partial
  failure in the probe unwinds what it had acquired** (`CudaGemm.unwind`), so a machine
  that declines at step 5 leaves no retained context or loaded module behind.
- **Per call, the device buffers are freed on every path** -- success, decline and failure
  alike, in a `finally`. Two tests pin it and they are not the same test:
  `aRunOfSuccessfulProductsFreesEveryBufferItAllocates` runs 1000 products that WORK and
  `aDeclinedProductCostsTheDeviceNothing` twelve that FAIL, which is the path the first
  never enters and the one that was wrong. Both bounds are two-sided -- free memory that
  GREW would mean the test is measuring the rest of the machine -- and deliberately loose
  (1.5 GB): `cuMemGetInfo` reports the DEVICE, not the thread, and the JVM backend's fork
  defines a separate copy of the binding per compiled class. Measured drift with the
  strided tier's tests in the set: 808 MB. Do not tighten without re-measuring.
- **On Metal the leak question changes shape** -- not "is every buffer freed" but "does
  the pool reach a steady state", which
  `MetalGpuTest.aRunOfCallsSettlesTheBufferPoolRatherThanGrowingIt` asserts over 400
  products after a warm-up.
- **Threads.** The driver API is thread-safe and every call owns its buffers, so
  concurrent members are correct without a lock; they serialize on the device anyway,
  because everything goes to the null stream. `DeviceResidency` is NOT thread-safe and the
  device attempt runs on the calling thread, which is why `--parallel` sits strictly below
  the device decision. One caveat left open: a copy issued while another thread's kernel
  is queued on the null stream waits for it, and waits INSIDE the critical window.
  Per-thread streams are the fix; nothing in the feature is threaded today.

### A DECLINE MUST COST THE DEVICE NOTHING, and that takes three calls in order

The invariant's sharpest edge, and the one place the first version of this library broke
it outright. A pooled allocation that FAILS still grows the pool as far as it can on the
way to failing, and hands back no pointer -- so there is nothing to free, and the pool
keeps the high-water mark for the life of the process AND against every other CUDA process
on the card. Measured before it was handled: one declined 80 GB product took a 128 GB
device from 69 GB free to 1 GB free, permanently, while returning `null` exactly as
designed and letting the CPU compute the right answer.

1. **A pre-flight.** The buffers' total is checked against `cuMemGetInfo` less 64 MB of
   headroom before anything is allocated. It measured 6-13 us a call in a training step
   (nsys, 7060 calls), so the answer is AMORTIZED: remembered, decremented by what was
   handed out, re-asked every 64 allocations or as soon as a request is more than a quarter
   of the remembered figure (`CudaGemm.allocate`). An estimate that errs does so towards
   REFUSING, and a request the stale figure lets through still lands on the trim.
2. **A trim after a failed allocation** -- three calls, in this order, or it silently does
   nothing: `release()` the buffers that DID allocate (a trim finds them in use otherwise
   -- measured, a declined product held 78 GB with the two swapped); `cuCtxSynchronize`,
   because `cuMemFreeAsync` is STREAM-ordered and the buffers are only QUEUED (measured, a
   trim before the sync returns `CUDA_SUCCESS` having freed nothing); `cuMemPoolTrimTo`.

With all three, twelve consecutive declined 80 GB products move free device memory by
0 MB. `GpuTest.aDeclinedProductCostsTheDeviceNothing` asserts the MEMORY rather than the
return value, because the return value was always right.

**Per-call allocation is the floor, and the feasibility spike measured around it.** Every
spike probe allocated once and looped, so its "~16-18 us floor" excluded allocation; a
per-call intercept needs three buffers a call. On the GB10 (`AllocatorCost.java`) a pooled
`cuMemAllocAsync`/`Free` pair is **0.7-2.3 us**, flat in the size, against **136-336 us**
unpooled; a whole f64 product at n=64 is 26 us pooled against 181. So the products
allocate through the driver's pool, fall back to `cuMemAlloc` only where the probe's trial
failed, and the size thresholds move with the floor when they do. **Metal has no such
pool**, and the cost there is not the allocate/release pair (1.2-7.7 us) but the pages
faulting in on the first write: a fresh-buffer n=512 product is 506 us against 308 pooled.
So that backend owns a size-classed pool (floor 4 KB, bounded by a quarter of
`recommendedMaxWorkingSetSize`), and its slabs are SCRATCH -- fully overwritten in, fully
read out -- which is what lets the pool need no invalidation rule of any kind. Residency
is the exception and it is a separate mechanism.

## `Linker.Option.critical` takes heap segments here too -- with a different bound

`.kb/linalg-blas.md` established that a `critical(true)` downcall accepts a HEAP
`MemorySegment`. `cuMemcpyHtoD` / `cuMemcpyDtoH` take
`MemorySegment.ofArray(a).asSlice(...)`
directly under it, and the offset rides along for free. Measured on the GB10, f64, one
`n x n` product end to end against the same path with the operands staged in a per-call
confined arena: 1.04x at n=8, 1.5x at n=128, 1.67x at n=256, **3.09x at n=1024** -- the
gap WIDENS with size, unlike `--blas`'s, because the staging buffer is a per-call native
allocation of the operand's size (an 8 MB `mmap` and page fault at n=1024). **So there is
no size at which staging wins, and the library never stages for the UPLOAD.** The download
is the opposite case and is staged; the reason is the fresh-page cost, under residency
below.

A critical call does not transition the thread to native, so the VM cannot reach a
safepoint while it runs. **A GPU has TWO ways for that window to get long, they need
separate rules, and neither rule is "stage it".**

1. **The copy is bandwidth-bound**, so a copy over `CRITICAL_CHUNK_BYTES = 1 << 26`
   (64 MB) is SPLIT into chunks rather than staged: the driver moves 64 MB, the thread
   becomes safepointable, the next chunk goes. 64 MB is ~1.1 ms of copy here (16.9 us/MB),
   and an extra downcall per 64 MB is nothing beside it. This is where a GPU can do better
   than `--blas`, whose library call is not divisible.
2. **A device-to-host copy on the null stream also WAITS for the kernel.** This has no CPU
   analogue and it is the trap: a critical `cuMemcpyDtoH` straight after a launch holds the
   thread off a safepoint for the kernel's whole runtime -- measured 36 ms at n=2048 f64,
   283 ms at n=4096, against 548 us and 2.2 ms after an explicit wait. Chunking cannot
   help, because the wait lands on the first chunk. So the kernel is awaited by a plain,
   thread-transitioning `cuCtxSynchronize` before the result comes back, whenever the
   launch is big enough for that to matter.

   **The threshold is per-device, because a flop count is not a duration.**
   `SYNC_FLOPS_PER_MULTIPROCESSOR = 1 << 22` times the SM count (which the probe already
   reads for its description). On the 48-SM calibration machine that is 2^28 flops, the
   ~0.6 ms the budget is meant to be; on a device with a quarter of the SMs the same
   duration is a quarter of the flops, and a fixed count would have put several
   unsafepointable milliseconds inside a critical copy there. The comparison is `>=`, not
   `>`: n=m=p=512 at f64 lands exactly ON 2^28 and has to be on the syncing side.

Every remaining critical path drains the launch queue first through `awaitQueued` -- a
plain, safepoint-friendly downcall -- so the critical window holds the copy alone. That is
what lets the post-launch wait be SKIPPED under lazy results, where nothing is downloaded
at the end of a call (`CudaGemm.awaitLaunched`); the `queued` flag is volatile and racy by
design, since a race costs one extra or one late synchronize, never a wrong answer. On
Metal none of this applies: a member stages through `MemorySegment.copy` into a shared
slab, so there is no critical window, and every call is `commit` + `waitUntilCompleted`.

## Declining on error, and the sticky rule

`CuResult` is the full CUDA 13 table -- **101 statuses, diffed against `cuda.h` at
`CUDA_VERSION 13000`** -- with one property this library reasons about: `sticky()`. The
human sentence is not duplicated; `cuGetErrorString` supplies it.

**Re-diff the table against the header when it is extended.** An invented constant is
worse than a missing one: an unknown code is treated as sticky and retires the feature, so
a constant that does not exist but IS in the table -- with a `sticky` flag someone guessed
-- can leave a dead context paying full round trips to fail. The first draft carried two
such (`917`, `918`, in no CUDA 13 header); they are gone.

- **Any non-zero status declines**, after freeing every buffer. `CUDA_ERROR_OUT_OF_MEMORY`
  is an ordinary decline: this call was too big, the next may fit.
- **A sticky status retires the feature for the process.** The seventeen marked statuses
  (launch failures, uncorrectable memory errors, a destroyed or deinitialized context, a
  driver mismatch) leave the context unusable, so every later call would pay a full round
  trip to fail; `CudaGemm` sets `usable = false` and `Gpu` answers "unavailable" without
  touching the driver again.
- **An unrecognised code is not an error condition of its own** -- a newer driver may
  return a status this table predates. `CuResult.of` answers `null`, `describe` still
  produces a string, and `isSticky` assumes the dangerous kind.
- **Metal has no such state.** A command buffer that ends in any status but `Completed` is
  an ordinary per-call decline; there is nothing to retire the feature over.

## Every threshold, and what fixed it

A threshold sits where the win is UNAMBIGUOUS, not where it first appears: a "win" that is
really a tie is the one way this flag can do harm. Every one below was measured against
the fastest CPU path the machine has (`--simd` on the JVM class output, JIT-warm), never
against a flop count, and re-derived per backend rather than inherited -- a 16-18 us floor
and a 77 us floor do not accept the same shapes.

| member | CUDA | Metal | what fixed it |
|---|---|---|---|
| product `n*m*p` | **2^17** (2^21 unpooled) | **2^22** | the CPU crossover: n≈45 at f64, n≈51 at f32 on the GB10 (n=48 is 23.0 us against 19.2, n=64 is 49.0 against 21.6); on the M4 Max n=128 is a tie at 178/183 us and n=192 is 4.4x |
| STACKED product `batch*n*m*p` | the same 2^17 | the same 2^22 | measured, the total-work point is the same as a single product's: the device is ahead at every shape at or above it including `256 x 8`, where each matrix is one tile. The floor really is paid once for the whole stack |
| element-wise map, elements | **2^14** (2^16 unpooled) | **2^17** | at the threshold every member taken is clearly ahead and every member refused clearly behind (below) |
| broadcast / axes transpose, OUTPUT elements | **2^15** (2^17 unpooled) | **2^18** | 1.2x at 16384 is inside the measurement; 2.1x at 32768 is not. On Metal a broadcast `sub` at 262144 is 1.75x and at 131072 is noise |
| axis fold, INPUT elements | **2^17**, and at least **256** output cells | **declined at every size** as a round trip | a fold with one output cell is a single-threaded device loop and loses to any CPU. On Metal, two independent refusals (below) |
| axis fold over a RESIDENT operand | 32 cells (one warp) | `MIN_RESIDENT_ELEMENTS` | there the CPU alternative is not a free walk but a DOWNLOAD |
| GEMV (`vec:matvec`), `rows*cols` | **2^17** (2^20 unpooled) | **2^21** | plus the two-sight rule, which no size can answer (below) |
| generator fill, elements | **2^13** | declined -- the member needs a `double` | 0.7-0.8x at 2^12, 1.6-1.8x at 2^13, 20-45x at 10^6 (`RngCrossover.java`) |
| the RESIDENT tier | any size | **2^14** elements | a launch with no copy; on Metal the per-command-buffer floor still applies (below) |
| MPS instead of our kernel | -- | 2^27 per matrix | 1.5x at n=512, 4.5x at n=2048; MPS carries ~35 us of object churn a call and loses below n≈448 |

**The element-wise rule is one sentence: a member is worth a round trip when its scalar
cost is a libm CALL, and not when it is a machine instruction.** The measurement that
produced it (`ElementwiseCrossover.java` over `elementwise-probe.cu`, which carries the
DECLINED candidates too so the refusals stay re-derivable, against
`elementwise-baseline.lisp`): at 1.5 M f64 elements `erf` is 124x, `tanh` 22.6x, `exp`
17x, `log` 13x, `sin` 9.3x -- and `sqrt` is 1.4x, the binary `add`/`mul` 1.15x. At f32 the
device column is FLAT (241-245 us for every member: nothing but bandwidth is left) and the
binary ops LOSE outright, 350 us on the CPU against 382. At the threshold itself the
cheapest member taken (`sin`) is 2.6x ahead and the dearest (`erf`) 42x, while `sqrt` and
`add` are BEHIND by 3.7x and 6x. **A member that wins by less than its own measurement
error is not a member.**

Two traps in the CPU column of any such table. **A CPU figure depends on which widths the
PROCESS has already run**, by 1.3-1.9x: a call site that has seen `double[]` and `float[]`
both is bimorphic, so f64 `exp` measures 9200 us in a both-widths harness and 7300 alone.
Never mix the two inside one row. And **the FIRST shape measured in a process pays ~500 us
a call for its first few thousand device calls** -- it is the device call path being
JIT-compiled, it survives best-of-three because all three rounds are inside the warm-up,
and it is why every benchmark file here runs a throwaway bench at each width first.

**The strided tier exists because the element-wise refusal was a refusal of a different
call.** The element-wise measurement refused `add`/`sub`/`mul`/`div` at EQUAL shapes,
where `--simd` runs a lane loop. Every one of those links in a real `softmax` or
`layer-norm` is a BROADCAST -- `(4 256 256) - (4 256 1)`, an array against its own row
reduction -- and `.kb/linalg-simd.md` says in as many words that the broadcast path is a
SCALAR ODOMETER walk in every `--simd` backend, "no lanes". Same for the axis folds and
for `transpose` with an axes list. So the CPU column those calls take is 3-8x the one the
element-wise round measured, and the same `linalg:sub` is a device member against a
`(4 256 1)` operand and a decline against a `(4 256 384)` one. The equal-shape refusal was
re-measured through this tier's own kernel and reaches the same answer (112.3 us at f32
against 85.0 on the CPU), which is why the tier is not a reversal.

**A DECLINED strided call must allocate nothing, and that is an ORDERING rule inside the
interceptor.** Unlike the product and the map, this tier sits on `linalg:add` / `sub` /
`mul` / `div` -- call sites a program runs constantly and which mostly decline. So the
size test comes FIRST, over a bound that costs nothing (a broadcast output is at least as
big as either operand; a transpose's output is the operand's own element count), ahead of
the broadcast-shape derivation and the permutation check, both of which allocate an
`int[]` the decline would throw away. The first draft did it the other way round.

## Precision

Three different breaks with the scalar defun, and they are not the same kind.

**The product FUSES.** `gemm.cu` keeps ONE accumulator per output cell and walks `k`
ascending across tiles and within each tile, which is exactly the scalar defun's order --
it does NOT reorder the reduction. What differs is that `acc += As[ty][k] * Bs[k][tx]`
compiles to `fma.rn.f64` / `fma.rn.f32`, so every term is rounded ONCE where the defun
rounds twice. Hence a few ulps rather than the `sqrt(n)`-ish growth a reordering would
give. Measured over random zero-mean inputs as a fraction of the largest cell of the f64
oracle: **3.4-5.6e-16 at f64 and 2.1-9.0e-7 at f32 from n=64 to 512** -- and the f32
column is the same distance a CPU f32 accumulation of the same product lands at, so **at
f32 the divergence is the WIDTH, not the GPU**. Over inputs exact at the operand width the
results match EXACTLY, which is what the exact-input tests assert; over inexact ones the
pin is a relative tolerance. A tuned BLAS fuses too and agrees with the device BIT FOR BIT
up to n=128 on this machine, separating only from n=192 where OpenBLAS blocks its `k`
loop -- so an order pin written at n=64 would be a tautology, and
`theDeviceIsAskedAheadOfATunedBlas` uses n=192 and says why.

**The transcendentals have their own libm, and that break can be SEEN.** Two correct
implementations of `erf` may differ in their last ulps and neither is wrong; on top of
that the device kernel evaluates AT the operand width (`expf`) where every CPU kernel here
evaluates in double and narrows on the store, because `emap`'s rule says so. Evaluating in
double on the device was refused: an f64 transcendental costs a consumer card 32-64x an
f32 one, so following the CPU's rule would make the width the hardware is FOR the slower
of the two. Worst per-element relative difference from the scalar defun over 400 samples
of a 16384-element linspace across each member's domain
(`elementwise-precision.lisp`, run once per flag and diffed), with the Metal column
measured over 262144 samples per member against the f64 oracle narrowed:

| member | CUDA `#d` (ulps) | CUDA `#f` | Metal `#f` | member | CUDA `#d` (ulps) | CUDA `#f` | Metal `#f` |
|---|---|---|---|---|---|---|---|
| `exp` | 2.1e-16 (0.9) | 1.3e-7 | 3.2e-7 | `asin` | 3.6e-16 (1.6) | 1.2e-7 | 2.3e-7 |
| `log` | 2.1e-16 (1.0) | 1.2e-7 | 2.3e-7 | `acos` | 2.2e-16 (1.0) | 1.2e-7 | 2.8e-7 |
| `tanh` | 2.2e-16 (1.0) | 1.7e-7 | 2.7e-7 | `atan` | 2.2e-16 (1.0) | 1.2e-7 | 2.2e-7 |
| `sin` | 2.0e-16 (0.9) | 1.2e-7 | 1.8e-7 | `sinh` | 2.2e-16 (1.0) | 1.3e-7 | 3.0e-7 |
| `cos` | 2.2e-16 (1.0) | 1.2e-7 | 1.7e-7 | `cosh` | 2.2e-16 (1.0) | 1.2e-7 | 2.8e-7 |
| `tan` | 2.2e-16 (1.0) | 1.7e-7 | 3.3e-7 | `erf` | **1.0e-15 (4.5)** | 1.1e-7 | 9.7e-7 |

**One to two ulps for eleven of the twelve**, with between 27 and 162 of 400 samples
differing at all, so most of the array agrees exactly and the disagreement is the last bit
of the rest. `erf` is the outlier at `#d` and the reason is on OUR side: the CPU oracle is
`%la-erf-1`'s A&S 7.1.6 series, not a correctly-rounded `erf`, so the device is probably
the more accurate. The feared 4.87e-5 on `tanh` from the feasibility spike **does not
reproduce at either width on either backend** and should not be quoted again -- but see
the Metal section, where two members needed a fix before that was true there. One
divergence is not a last-ulp one and can be seen: `(linalg:erf #d(-0.0))` above the
threshold prints `-0.0` on the device and `0.0` elsewhere, the same signed-zero wart
`.kb/linalg-simd.md` records for wasm's `erf` and the same cause.

The pins are **1e-12 relative at `#d` and 1e-5 at `#f`** -- three to four orders above the
measurement, the same posture `TorchGradcheck`'s 1e-3 has: loose enough never to flap,
tight enough that a fast-math build, a mis-numbered op code or a lost `-arch` fails
instantly.

**The strided, resident, index and copy tiers do NOT widen the break: they are
BIT-IDENTICAL to the scalar defun.** Their kernels read widened to double, compute in
double and narrow only on the store -- `%la-bcast-loop`'s and `%la-fold-axis`'s own rule
-- and hold no libm at all; a gather and a copy move values, so nothing rounds. On Metal
the same claim has to be EARNED rather than inherited, and the software binary64 route
that earns it is in that section. `sqrt`'s NaN is the one wrinkle: the device signs it and
`Math.sqrt` does not, so the kernel canonicalizes.

**The one member that CANNOT be bit-identical is the clip norm, and it says so.**
`%la-sum-squares` is a whole-array reduction: every other reduction here keeps its
caller's order by giving each output cell one thread, and a whole-array sum has ONE cell,
so that trick has nothing to divide. Three ways out were weighed -- a fixed blocked order
both sides use (rejected: no single order is good on both machines, and the defun is the
cross-backend oracle every other backend would have to follow into it), leaving it on the
host (278 MB a run of downloads), or breaking the order and saying so. The kernel folds a
grid-strided slice per block in a `double` accumulator, tree-adds within the block, and
the host adds the partials in block order from the caller's seed. Every term is rounded
exactly where the defun rounds it (`__dmul_rn`, `__dadd_rn`); only the ASSOCIATION
differs, and a tree is the better approximation of the two. The block count is
`min(1024, ceil(n / 256))`, a pure function of the length, so the answer is REPRODUCIBLE
run to run. **The contract line: under `--gpu`, `torch:clip-grad-norm`'s norm is within a
few ulps of the norm every other backend computes, and is not equal to it.** The pin
asserts closeness and reproducibility, not equality.

### The check that replaced byte-identity

"Byte-identical with the flag and without it" was the acceptance check until the
transcendentals landed, and it stops being the right one for any program that touches one
over 16384 elements. **`--gpu` is the first flag whose results a user should not expect to
match the other backends elementwise**, and the guide says so. Three checks replace it and
together they are strictly stronger:

1. **Byte-identity still holds and is still asserted everywhere the device is not asked**
   -- below each threshold, for the refused members at any size (over a million elements),
   and for an equal-shaped binary pair at any size. Those last two are the guard on the
   measurement: they fail the moment someone widens the member set without measuring it.
   **And it holds where the device IS asked, for the whole strided tier**
   (`theStridedTierIsByteIdenticalWithTheFlagOnEveryMachine`) -- a stronger claim than the
   one it replaces, not a weaker one.
2. **Above the threshold the pin is the relative tolerance above**, per element, asserted
   on EVERY machine -- on one without a device the difference is exactly zero, so the same
   test carries both worlds.
3. **`CUDA_VISIBLE_DEVICES=` still makes every flagged run byte-identical to an unflagged
   one**, because the probe then finds no device and every member declines. That is the
   check that the flag is doing nothing behind the scenes.

`--gpu` **stays out of `ci-spec.yaml`** and the scalar `linalg.lisp` defun remains the
cross-backend oracle, exactly as for `--blas`.

## Device residency: the arrays stay on the device

The design that turned this flag from "a fast matrix product" into an accelerator, built
over five rounds in 2026-08-22/23 and measured at every step. **Do not change any part of
it without reading the two enumerations below**: they are what makes a cache of device
copies sound, and each has a pinning test on each backend.

**What it is.** `DeviceResidency` maps a host array -- the primitive `double[]` /
`float[]`, by IDENTITY, since that is the one object both interceptors already unwrap --
to a device buffer holding a copy of its elements, with the span it mirrors (`offset`,
`bytes`; a different span is a miss). Every member looks each operand up before it
allocates (a hit is the launch pointer and no upload) and records what it uploaded or
produced. The handle CANNOT live in the array: on the interpreter a packed array is a
record and a field could be added, but on the JVM class output the array IS a bare
`float[]` with its header inside it, and there is nowhere to put one. Identity is what
works on both -- and it is sound rather than merely likely because
`make-array :displaced-to` rejects a packed array outright, so no second object can write
another's storage.

**Buffers are freed at the two moments a stream-ordered free is safe to enqueue**: the
start of a call, before any operand is looked up, and the end of one, after the launch and
the download. A free enqueued BETWEEN an operand's lookup and its launch would be ordered
ahead of the kernel that reads it. `Gpu.written(host)` drops an entry and QUEUES its
buffer without a driver call -- it runs on whichever thread wrote the array and needs no
context.

**The keys are WEAK, and that was the first correction.** The first build held its keys
strongly and let the LRU decide; the training step got **2.3x slower**. Every activation
and gradient the step allocates stayed reachable from the cache, the heap grew to 14 GB,
and the driver's pool grew with it one cold allocation at a time. A cache keyed on an
array's identity has no meaning once the array is unreachable, so the key is a
`WeakReference` with an identity hash, a collected key turns up on a `ReferenceQueue`, and
the next drain frees its buffer. `LinkedHashMap` in access order over those keys is the
LRU; a lookup presents a transient `Lookup` whose `equals` matches by referent, so a
lookup allocates no reference.

**A device copy to or from a host page the GPU has never touched costs ~9 us per 4 KB, and
that fact decides two things** (`FreshPageCost.java`, the decisive probe of the round). An
HtoD from a fresh 1 MB Java array is 2.3 ms median against 25 us over a reused one; a DtoH
into one is 50 us median with a fifth of them over 2 ms. The GB10 answers
`PAGEABLE_MEMORY_ACCESS_USES_HOST_PAGE_TABLES` 1: the copy engine reaches pageable memory
through the CPU's page tables and the first translation of each page is the cost. What
warms a page is a CPU copy INTO it -- not the JVM's zeroing, not a store per page.

1. **Every DOWNLOAD is staged** through one pinned 16 MB bounce buffer (`cuMemHostAlloc`
   at probe time, so every leak test's baseline includes it; a non-critical
   `cuMemcpyDtoH` into it and `MemorySegment.copy` out, 16 MB at a time, under one lock).
   A result array is always fresh, so every direct download landed on cold pages. **The
   UPLOAD stays direct and critical**: its source was just written by the CPU, and that is
   warm by the same probe.
2. **The eager budget is a CAP on the pool, not a share of the card.** Left at a quarter
   of free memory (~30 GB) nothing was ever evicted before the collector got to it, every
   allocation grew the pool (5 us a call instead of 1), and the run was SLOWER with half
   the uploads than with none. Capped at 64 MB, 256 MB or 1 GB it was 5-10% faster than no
   residency and the three were within noise, so the budget is `min(free / 4, 1 GB)`,
   re-derived at every pre-flight refresh. **The cap is what keeps the driver's pool
   recycling its warm blocks; it is not a safety margin.**

**Residency can slow a call by one upload but must never turn it into a decline.** The
pre-flight evicts everything the call is not holding, trims the pool, and asks again
before it would refuse.

### The two seams, and what must report through them

`written` and `materialize` are residency's CONTRACT on the caller, not conveniences.
**Every in-place write to a packed array's storage must come through `written` BEFORE it
lands**, or the next call answers for bytes the array no longer holds -- and, lazily, a
dirty copy's download would clobber the store. **Every host READ of packed storage must
come through `materialize` first**, or it reads the zeros of an array nobody filled. Both
are cheap when they do not matter (a volatile read with nothing resident or nothing dirty,
then an identity compare) and never run the probe.

**The interpreter has ONE seam of each.** `LispSingleFloatArray` / `LispDoubleFloatArray`
call `FloatArrayAccessHook.written` from `setElement` and `.read` from the records'
`data()` accessor -- so `aset`, `row-major-aset`, `fill`, `replace`, `aref`, the printer,
`toGeneralArray`, `read-sequence`, a record pattern and Java interop all pass through it.
The hook is a static in the ROOT package and must not name any accelerator: `-Pweb`
substitutes `eval/LinalgGpu`, whose `install` is what points the hook at
`LinalgGpuKernels`. The one reader that must NOT go through it is the device interceptor
itself, which takes `storage()`.

**Three kinds of writer bypass the setter and report themselves**, and each was found
the hard way: the `--simd` in-place kernels (`%la-adam-step`, `%la-scatter-rows`,
`%la-scale`, `%la-rng-fill`), `vec:`'s whole `-into` family, and the bulk
`%read-sequence-packed` behind `read-sequence` over a packed array -- it fills storage
through a `FloatBuffer` view, so a grep for writes through `.data()` saw a READ, and it is
how every model weight arrives. One case looks like a writer and is not: `torch:set-data`
REBINDS a tensor's data field rather than writing into the old array, so it invalidates
nothing.

**The JVM class output has no seam and ENUMERATES instead**, through `_gpuWritten` /
`_gpuMaterialize` (each guarded by `if (_gpuInited != 0)`, which is what lets `_fvAset1`
be emitted before the bridge class is defined): `_fvAref1/2/N`, `_fvAset1/2/N`,
`_fvToGeneral` / `_fvToGeneralPrint` (the printer, `equal`, every coercion); every
argument of every accelerated `linalg:` call site, right after the device attempt and
before any host rung; every argument of every `vec:` call site, the `-into` destination as
written; the typed loops at `hoistArrays`, once per array, since the arrays are
loop-invariant; `_readSeqPacked` / `_writeSeqPacked`; and every argument of a Java interop
call, which reads a packed array raw. `_fvDims` / `_fvLength` / `_fvElementType` read the
header only, which is written at allocation and never stale.

**The pins.** `everyEnumeratedWriterInvalidatesTheResidentCopy` and
`everyEnumeratedReaderMaterializesTheDeviceResult`, on EACH interceptor: one program that
makes operands and results resident with a bit-identical member, then writes through every
enumerated setter and reads through every enumerated reader, printing after each, run
against the same program with no flag. **These are the tests to extend when a new in-place
writer or a new raw reader of a packed array is added anywhere in the tree.**

### Lazy results, and the result that has no host array

`Gpu.lazyResults(true)` makes every member's `finish` skip the download: the result buffer
is recorded as the array's DIRTY copy (the device holds the bytes, the host array does
not), and an in-place member marks the buffer it wrote rather than recording a second one.
A dirty copy comes home through exactly one operation, `Gpu.materialize`. A clean copy
stays resident for the next member, so a chain `matmul -> div -> where -> softmax ->
matmul` moves nothing over the link until something on the host reads a link of it, and
then moves only that link. Off by default, so the library's own contract ("`out` is filled
when the call returns") holds for any other embedder; `Gpu.lazyResultsIfWorthwhile()` is
what the interceptors call, and it asks the backend (`GpuDevice.lazyResultsPay`: `true` on
CUDA, `false` on Metal, and the Metal section has the measurement).

**The device never drops a dirty copy on its own.** Every path that lets an entry go --
the LRU eviction, the pre-flight's `evictAll`, a replacement at a different span -- turns a
dirty one into a `Flush` (the host array held STRONGLY, the pointer, the span), and the
owner downloads it IMMEDIATELY after the call that produced it: between the drop and the
download the array has no entry and a reader would see nothing to materialize. The pointer
is QUEUED rather than freed, because an eviction inside `stage` runs BEFORE the launch
that reads the buffer. The LRU evicts CLEAN copies first and a dirty one only when no
clean one is left: a clean copy costs at most one later upload, a dirty one costs a
download now. `lazyResults(false)` brings every dirty copy home first.

**The lazy budget is not the eager cap, and that is measured twice.** The first lazy build
kept the 1 GB cap and was SLOWER than the eager build it replaced: the autograd graph
keeps a step's activations reachable until its backward, so with ~400 MB of dirty results
live the cap evicted them as fast as they were made, and the step paid the download AND
the re-upload. Lazily the budget is everything the device has less an eighth of it (never
less than 512 MB; `LAZY_HEADROOM_SHARE`), refreshed at every pre-flight. At the book's
shapes a quarter share flushed 45 GB of the graph during backward and the headroom rule
flushed nothing.

**A lazy result allocates no host array: it is a STUB.** The value the program holds is
still the array, but SHORT -- the header alone (`[rank, dim...]`, 3 floats for a matrix)
on the JVM, an EMPTY `float[0]` / `double[0]` on the interpreter, distinct per result.
Every header-only reader (`array-dimensions`, `length`, `array-rank`,
`array-element-type`, the type predicates, the printer's prefix) works on it unchanged,
which is the whole reason it is a short array of the same type rather than a new kind of
object; and the stub is the IDENTITY residency is keyed on, because it is the object the
program's variables, conses and closures capture. The elements live on the device while
the entry is dirty and -- from the first host touch -- in a BACKING the library allocates
(`DeviceResidency.storageFor`: the full span, the stub's prefix copied in) and holds in a
second weak-keyed map for as long as the stub is reachable. Four rules keep it sound:

- **A stub is told from a full array STRUCTURALLY**: a result array exactly the prefix
  ahead of the result offset (`Gpu.fitsResult`: `length == offset`) is a stub, one long
  enough to hold the span is a full array, anything between is a caller's mistake and
  declines.
- **A stub offered as an OPERAND has the extent of the span it stands for**
  (`GpuDevice.extent`: its own length, or the end of its entry's span, or its backing's
  length, whichever is larger). The first build asked `a.length`, so every stub operand
  and every stub result declined and the interceptors fell to their host rungs.
- **A stub is in one of three states and never a fourth** -- a dirty device copy and no
  backing; a copy and a backing; a backing alone. Every path that lets a dirty copy go
  flushes it into the backing first; a stub with neither is a broken invariant and
  `source` throws rather than uploading zeros.
- **The two seams ANSWER the array to use.** `materialize` and `written` return `Object`:
  the host array itself, or a stub's backing. On the interpreter nothing moved -- `data()`
  answers what the hook answers -- except that the in-place `--simd` kernels report
  `storage()`, the stub, and not the array `data()` handed them (the first build reported
  the backing, the stub's entry stayed clean and stale). On the JVM every enumerated site
  REBINDS its local to the answer, and `_fvLength` at rank 1 reads `d[1]` rather than
  `d.length - 2`, because a stub has no Java length to speak of.

**The unswap rule: a host rung that answers its argument answers the CALLER's object.**
The lane kernels and defuns that write an argument in place answer that argument, and
under this mode the argument they were handed is the BACKING. Let that escape and the
program holds two objects for one storage -- the stub in its variable, the backing in the
result -- and a device member offered the backing keys a second entry that a write through
the stub never invalidates: a silent stale read. So every call site that hands a backing
to a host rung maps the answer back through `_gpuUnswap(result, original, handed)`. The
interpreter has no such problem, because its value is the RECORD and the backing never
leaves `data()`. **The one hole this leaves, named rather than closed, is Java interop:**
Java is handed the backing because it reads the array raw, and Java may STORE it as well
as answer it.

**The fast paths remember FOUR arrays, not one.** `materialize` and `written` are called
once per element from an `aref` / `aset` loop, so each short-circuits on "nothing dirty" /
"nothing resident" (a volatile read) and on "the array I answered for last time". A single
remembered array was not enough: a loop that reads one array and writes another
(`concatenate`'s defun, a typed `dotimes` over two) alternated and took the monitor on
every element -- a third of a training step's samples. The read ring holds `(host,
storage)` PAIRS as one immutable object per slot, so a reader racing the writer never sees
one host's storage under another's.

### The tiers that exist only over a resident operand

With nothing coming home, the members this file REFUSED because a round trip cannot beat a
lane loop became launches with no copy. **Every one is offered ONLY over an operand the
device already holds** (`Gpu.resident`, a lookup without a hit count), declined otherwise
at any size, so the refusals' measurements stand untouched. All of them are bit-identical
to the CPU kernels they replace.

| member | `linalg:` shape | kernel |
|---|---|---|
| `zip(op, a, b)` | `add` `sub` `mul` `div` `maximum` `minimum` and the five masks at an EQUAL shape | `zip_fXX`, `bin_op` in double |
| `scale(op, a, s, swap)` | the same eleven with a SCALAR on either side | `scal_fXX`, the scalar a double whatever the width |
| `map(MAP_SQRT .. MAP_SIGN)` | `sqrt` `abs` `negative` `sign` | the map kernel's cases 12..15 (`MAP_LIBM_OPS` = 12 is where the size threshold stops applying) |
| `where(m, x, y)` | `linalg:where`, hence `torch:masked-fill`; any operand a scalar | `where_fXX` over a 4-stride layout |
| `adamStep(x, g, m, v, rule)` | `%la-adam-step`, IN PLACE: x, m, v stay resident and dirty | `adam_fXX`, every step an `_rn` intrinsic so nothing contracts into an FMA |
| `copy(a, sa, out, so, dims)` | `reshape` (hence `expand-dims`, `squeeze`, `flatten`), the rank-2 `transpose`, `%la-gather-strided` (hence `slice`, `broadcast-to`), `concatenate`, and `%la-scale` in place | `copy_fXX`: one source and one destination stride per axis, either sign |
| `takeRows` / `gather` / `scatterRows` | `take-rows`, `gather`, `%la-scatter-rows` | `take_fXX` (two modes) and `scatter_fXX` |
| `sumSquares` | `%la-sum-squares` behind `torch:clip-grad-norm` | `sumsq_fXX`, the ONE member whose fold order is not the caller's (above) |

The size-thresholded members also take a resident operand at ANY size (`worthOrResident`),
because the trip the threshold exists to amortize is not being paid.

**`scatter_fXX` is the one that needed a design.** The CPU adds slab `i` of the gradient
into slab `idx[i]` of the table for `i` ASCENDING, and a token embedding's indices repeat
(1024 tokens over a 138-character vocabulary), so the order IS the value and atomics would
lose it. The kernel keeps it without atomics by turning the parallelism inside out --
**one thread per DESTINATION cell, not per source element**: `Gpu.scatterRows`
counting-sorts the indices by destination first (stably, so each group is ascending) and
hands the kernel `start[rows + 1]` followed by the grouped source slab numbers, and thread
`(r, k)` walks its own group in the defun's order over a cell no other thread touches. It
also inverts the traffic: the destination is a FRESH zero table, so the device pays an
upload of 0.2-0.4 MB instead of a download of 1.9 MB, and the table stays resident for the
clip and the Adam step, which is where the gradient was going anyway.

### The GEMV, and the matrix that stays

The first member outside `linalg:`: `vec:matvec`, which is what `examples/llama2`'s decode
loop is made of (79 GEMVs a token for `stories15M`) and which this file had declined twice
as "memory-bound, so its whole cost is one pass over an operand the device would have to
be handed anyway". Both declines were right per CALL and wrong per TOKEN once the matrix
does not move.

**The rule that decides the upload is not a size.** The first sight of any matrix declines
and leaves a MARK -- an entry with no buffer, which counts for nothing in the budget,
frees nothing when dropped, and is cleared by `written` exactly as a copy is; the second
sight of the same span, unwritten, uploads it; every later one is a hit. So a model's
weights are resident from their second token on, and a matrix the program REWRITES between
calls (llama2's KV cache, a Jacobian recomputed per step) is "first sight" every time and
never pays the cold trip it would lose -- measured, 0.87x at 384x384 f32 cold. The
alternatives were a threshold high enough for the cold trip to win (2^19-2^20, where
llama2's 768x288 matrices never reach the device at all) or a bet that the first upload is
repaid (it is for every weight, and is not for every rewritten matrix in the 2^17-2^19
band). The mark costs one `LinkedHashMap` entry per distinct matrix offered.

**The accumulator is a double at both widths, and that was measured too.** Against the
scalar defun's rule -- a double sum narrowed on the store -- over 1024 rows of 768 inexact
floats: the double kernel is bit-identical on **1024 of 1024** rows, a float kernel on 268,
and the `--simd` lane kernel on 144. The reason is arithmetic rather than luck: the product
of two floats is exact in double, so what separates the device from the defun is only the
ORDER of a double sum, which moves the narrowed float only when the sum lies within ~1e-16
of a rounding boundary. It costs ~2 us on a small resident call and buys a result CLOSER
to the cross-backend oracle than the lane kernel it replaces -- which is what lets
llama2's story stay byte-identical with the flag on. It is pinned as a relative tolerance
plus "more than 99% of rows identical", not as byte-identity, because it is not one.
(Metal has no double and reaches the same 1024 of 1024 with a compensated float pair; that
section has it.)

**The seam is a CHAIN on both backends.** Interpreter:
`LinalgGpu.installVec` is called from the VEC library's lazy-load hook after
`VecSimd.install` and `define`s `vec:matvec` over whatever is bound -- the lane native or
the defun -- with the same declined-input protocol as every `linalg:` member, and installs
the write hook itself, since a program may never reach `linalg:`. JVM: `JvmExprCompiler`
routes a `vec:matvec` call site to `JvmSimdCompiler.compileGpuMatvec` whenever the GPU
bridge was emitted -- with `--simd` or without -- which emits the device attempt over temps
and on its `null` the lane kernel or the spliced defun. Declined: anything that is not a
packed rank-2 matrix and a packed rank-1 vector of the same width and matching extent, a
mixed pair (which the defun COMPUTES and the lane kernel refuses -- both outcomes are the
captured binding's), and the first sight.

### The collector, and the flags that do and do not help

**On CUDA the library ASKS for a collection, and that request earns 350% while costing 3%.**
Stubs made the collector stop running: a stub is twenty bytes, the young generation that
had filled every eighty results now takes minutes to fill, and the stubs a step had dropped
-- with their 25-100 MB device buffers behind them -- stayed resident until the pool
reached its budget, where the LRU evicted them by DOWNLOADING into fresh backings, the
allocation the mode exists to avoid. So the LRU evicts CLEAN copies on its own and, when
only DIRTY ones are left, STOPS and sets `collectionWanted`; the owner then runs
`System.gc()` -- the JDK's own direct buffers are the precedent, off-heap memory governed
by small Java objects and collected on demand when their limit is hit -- drains what the
collector released, and only then evicts what is still over budget, as flushes. A
collection is asked for at most once per eighth of the budget PRODUCED since the last one
(`COLLECTION_SHARE`, floor 64 MB), so a live set that genuinely exceeds the budget flushes
rather than collecting on every call. **The control that keeps the policy:
`-XX:+DisableExplicitGC` makes the book's-shape run 4.5x slower** with only 16 s of pauses
in it -- with the request refused the LRU has nothing but dirty entries to evict and
flushes live-looking dead results into fresh backings.

**What a collection COSTS is the pages, not the collection.** A full collection here is
50 ms under either collector and total pause time is 3% of the run under both, so the 20 s
between the two collectors over a 103-step run is not collection work at all. What it is:
a compacting full collection moves every live array to a new address and hands the regrown
heap fresh pages, and a device copy to or from a page the GPU has not touched costs ~9 us
per 4 KB. `-XX:+ExplicitGCInvokesConcurrent` -- which never compacts -- recovers all of it
while RAISING the number of requests from 24 to 97 and LOWERING pause time. One premise
worth correcting for the next reader: "almost nothing is allocated per step" is true of the
LIVE SET (143 MB) and not of the allocation rate (~1.9 GB a step, all of it dying).

**So the rule, not a recipe: the heap's pages have to be ones the program recycles.** Two
configurations satisfy it and neither wins at both shapes, so what the guides print is the
rule: **hand-size a young generation only where the program FILLS it** (`-XX:+UseParallelGC
-Xmn8g` where a step allocates gigabytes -- 1.9 GB a step turns it over every four -- is
the fastest thing measured at the book's shapes), **otherwise leave the collector alone and
add `-XX:+ExplicitGCInvokesConcurrent` to a long run**. Two traps: `-Xmn` sized for the
wrong shape is worse than no `-Xmn` at all (4 GB of pages the device never touches, 57%
slower at the notebook's width), and **`-XX:+AlwaysPreTouch` under G1 is a disaster** (4x
at the notebook's width) because G1 pretouches every heap expansion INSIDE the pause.

**None of the above is true on Apple silicon, and following it costs 13%.** The request is
gated on the LRU having only dirty copies left, which is a lazy-mode state; eagerly -- and
that backend is eager, by measurement -- the only resident array is a GEMV matrix, held
clean. `-Xlog:gc` over both shapes says so in one column: **`System.gc()` appears ZERO
times in every configuration at both shapes.** So `-XX:+DisableExplicitGC` costs nothing
rather than 4.5x, `-XX:+ExplicitGCInvokesConcurrent` answers a question nobody asks, and
the pages argument has nothing to act on -- the pool's slabs ARE host memory, the GPU reads
the pages the CPU wrote, and there is no such thing as a page the device has not touched.
Measured on the M4 Max, 23 steps at the book's shapes, two rounds each:

| flags | 23 steps | pauses (full) | total pause | `System.gc()` |
|---|---|---|---|---|
| **default collector (G1)** | **177 / 183 s** | 194-219 (0-1) | 1.7-2.0 s | **0** |
| ... plus `-XX:+ExplicitGCInvokesConcurrent` | 179 / 182 s | 186-222 (1) | 1.8-2.2 s | 0 |
| ... plus `-XX:+DisableExplicitGC` | 179 / 187 s | 170-227 (1) | 1.9-2.7 s | 0 |
| ... plus `-XX:+AlwaysPreTouch` (`-Xmx32g`) | 197 s | 643 (14) | 9.7 s | 0 |
| `-XX:+UseParallelGC -Xmn8g` | 204 / 205 s | 391 (14) | **26.2 s** | 0 |
| ... plus either flag above | 203-204 s | 390-392 (15-45) | 26.0-26.4 s | 0 |
| `-XX:+UseParallelGC`, young adaptive | 190 / 191 s | 215-221 (22) | 16.1-16.6 s | 0 |

At the notebook's width, 200 steps, the same eight rows land within one and a half per cent
of each other (22.0-22.3 s; the adaptive parallel row alone at 23.5), where the CUDA table
at the same width spanned 5.4 to 22.9 s. **On a Mac: set `-Xmx` and stop.** Nothing in
`DeviceResidency` is per-device -- a collection policy would be a `GpuDevice` question, and
there is no policy to decide while the request is never made. It becomes live again the
moment `.todo/495` makes lazy results pay there.

## The CUDA backend

**Thirty-four entry points in `gemm.cu`** (eleven in `gemm.metal`, which has no f64
sibling and no generator), each taking its member as an op-code PARAMETER: the products
(`gemm_f64/f32`, the batched pair, and two register-tiled f32 siblings), the element-wise
`map_f64/f32`, the strided `bcast_*` / `gather_*` / `fold_*`, the generator `rng_fill_*`,
the GEMV `gemv_*`, and the resident tier's `zip_*` / `scal_*` / `where_*` / `adam_*` /
`copy_*` / `take_*` / `scatter_*` / `sumsq_*`. A batched kernel
is six lines -- it offsets the three pointers by `blockIdx.z` times the strides and calls
the SAME `gemm<T>` device function -- which is why a batched cell folds `k`
bit-identically to an unbatched one and the precision contract needed no second sentence.
`batch == 1` still launches the PLAIN kernel with the parameter block it always had.

**One stride per operand, not an offset table -- and the decline that buys.** The CPU
kernel walks the batch axes as a mixed-radix odometer; the device adds
`blockIdx.z * stride`. The two agree exactly when every axis's stride is that one stride
times the axis's own weight in the counter, which is true for a contiguous batch of any
rank and for a wholly broadcast operand (stride 0), and false for a broadcast axis sitting
UNDER a non-broadcast one -- `(2 1 40 40) x (2 3 40 40)`, whose `a` offsets go
`0,0,0,base,base,base`. The interceptors derive the stride in O(rank) and answer -1 when
no single stride reproduces the odometer; -1 is a decline like any other. A per-batch
offset table would have been fully general, one more allocation and copy per call, and
would have made the common case pay for a shape no example has.

**The strided layout rides BY VALUE in the parameter block on both backends**, and the two
moved it for different reasons. On CUDA a broadcast needs `3 * rank` ints and phase 3 put
them in a small pooled buffer, pricing the allocation and missing the COPY: the 192-byte
`cuMemcpyHtoD` is synchronous, so it ordered behind every kernel already queued on the null
stream and each strided call was a hidden `cuCtxSynchronize`. It is now a fixed
`strided_meta` struct (`4 * Gpu.MAX_STRIDED_RANK` = 64 ints, the unused tail zero, because
`cuLaunchKernel` copies the declared parameter size). `take` and `scatter` keep their index
BUFFERS, because an index list has no fixed size.

### The register-tiled f32 GEMM

The 16x16 shared-memory tile moves one element of each operand through shared memory per
multiply-add, which on the GB10 is ~2.3 TFLOP/s against an f32 peak near 23.
`gemm_tiled<T, TM, TN>` is a `16*TM x 16*TN` block tile, 16x16 threads, each thread owning
a `TM x TN` patch at rows `ty + i*16` and columns `tx + j*16` -- so a warp's global loads
and stores are contiguous and its shared reads conflict-free -- with operands staged
through shared memory 16 deep in k. Two entry points, `gemm_batched_f32_t4` (64x64) and
`_t8` (128x128), both taking the batched parameter block at every batch size including 1.
**f32 ONLY**: at f64 the scarce double units pin every tile to the same speed and the 8x8
tile spills registers and LOSES.

**The fold is the 16x16 kernel's, bit for bit, and that is what makes the choice free.**
Each cell accumulates k ascending from +0 through one `fma.rn.f32` per term over K rounded
up to 16 with zero padding, which is exactly what `gemm<T>` compiles to, and a padded term
is `fma(0, 0, acc) = acc`. `gemm-tile-probe.cu` found zero differing cells;
`everySingleFloatProductKernelLandsOnTheSameFusedFold` pins it against `Math.fma` at shapes
that reach each tile, with M, N and K all off the tile and off 16. So `CudaGemm.tileF32`
may choose by SPEED alone.

**The rule, in SMs so a smaller card moves with it: the 128 tile when both output axes are
at least 128 and the grid has at least `SMs / 2` blocks; else the 64 tile when both are at
least 64 and the grid has at least `SMs` blocks; else 16x16.** Three regimes, from the
probe: the 128 tile is 2.2-4x once its grid holds about half the SMs (an MLP-up
`4 x 256x384 . 384x1536` is 492 -> 124 us, a 2048-square 6929 -> 1991) and loses below that
(a 256-square is 19 -> 54); the 64 tile is the middle rung and the one that takes a batch
of SHORT rows (the book's batch-64 of 64-row slabs, where a 128-row tile wastes half of
itself); the 16x16 kernel keeps everything small and everything f64. The `#pragma unroll`
the probe carried was dropped: ptxas unrolls the k loop itself at the same speed, and the
pragma was 60 KB of PTX.

### The launch pipeline, and what a step is actually bound by

The step is **device-bound**: profiled over the training program at the notebook's shapes,
0.77 s of which ~0.72 s is device kernel time. The "0.2 s of arithmetic" a FLOP estimate
suggests ignores where the time goes on this card -- memory passes, and a product without
tensor cores. What the profile found on the HOST side were two hidden serializers, both
removed: the post-launch `cuCtxSynchronize` (which predated lazy results -- under them
nothing is downloaded at the end of a call, so the wait only idled the host) and the
strided layout copy above. Per step they took 817 syncs and 1056 `cuMemcpyHtoD` down to
**53 and 57** -- the survivors are genuine operand staging, each draining the queue first
through `awaitQueued`. The pipeline can now run a few kernels deep.

**Where the rest goes, and it is not launches.** Launches are ~2.5 us of API each and
overlapped. Against PyTorch on the same card, decomposed the same way, two findings
reframe the comparison: its "eager fp32" GEMMs are **TF32 TENSOR CORES** (the container's
default), a precision class we deliberately do not use, which is ~2.5x of the gap and not
a launch or a fusion story at all; and the rest is **PASS COUNT**, not launch overhead --
its element-wise ops are FUSED single kernels where we pay one full memory pass per
`linalg:` member (a dropout at the score shape is four passes over 100 MB where PyTorch
pays one). The fusible set (softmax / layer-norm / dropout / GELU, forward AND backward)
is worth roughly 100-150 ms of a 700 ms step, and backward fusion means hand-written
adjoints in `torch.lisp` -- a tape-semantics change that moves CPU outputs too. Filed as
`.todo/499` rather than built, because even a perfect fused softmax forward is 3% of the
step. The end-to-end figures live in the guide and in
`examples/llm-from-scratch/README.md`.

**The copy route was measured and KEPT as it is.** The GB10 answers
`CU_DEVICE_ATTRIBUTE_INTEGRATED` 1 and `PAGEABLE_MEMORY_ACCESS` 1, so a kernel can read
host memory directly, and `ZeroCopyRoute.java` priced four alternatives to today's
critical copies. A kernel over host memory would be **4x** on every op at 1 M elements and
is UNREACHABLE: it must have the array pinned for its whole run, and FFM's `critical` pins
for one downcall only. The only safe zero-copy is through pinned host buffers, and the
Java `MemorySegment.copy` into them runs at 35-60 GB/s single-threaded, slower than the
driver's own pageable copy -- so it loses past 262144 elements and wins 17% at 65536.
Neither pays for a pinned pool and its budget. **Any future change to this route re-runs
that table first.**

## The Metal backend

The same feature with a different member set. The flag, the CLI, the interception layer,
the decline protocol and the tests are shared; what is NOT shared is the width, every
threshold, and two whole tiers.

| | CUDA | Metal |
|---|---|---|
| widths | `#d` and `#f` | **`#f` only** -- MSL rejects `double` outright |
| rank-2 product | our tiled kernel | **MPS** above `2^27` per matrix, our tiled kernel below |
| stacked product | our batched kernel | our batched kernel |
| element-wise tier | twelve members | the same twelve |
| broadcast + axes transpose | yes | yes |
| axis fold `:axis` | yes | **not as a round trip, measured**; over a resident operand only |
| generator fill | yes | no -- it needs a `double` |
| `vec:matvec` | from `2^17`, double accumulator | from `2^21`, **compensated float** accumulator |
| lazy results + resident tier | on (`lazyResultsPay`) | **built, pinned, and NOT switched on -- measured** |
| resident set | every operand and result | **the GEMV's matrix only** |
| index tier + clip norm | yes | declined: with no lazy results there is no download to save |
| per-call floor | 16-18 us | **77 us**, per COMMAND BUFFER |
| per-call memory | the driver's pool | **our own** size-classed pool |
| kernels | PTX generated at build time, checked in | MSL compiled at RUN time, from a string |

**The dispatch seam** is `GpuDevice`, a package-private sealed interface over `CudaGemm`
and `MetalGemm`; `Gpu` is unchanged above it. Two questions cross it that did not exist
before: `supportsDouble()` -- so a `#d` operand is a decline rather than a slower path --
and `thresholds()`, because a 16 us floor and a 77 us floor do not accept the same shapes
and a single constant would have been wrong on one of the two. `lazyResultsPay()` joined
them later.

**Single float, or nothing.** MSL rejects `double` outright, so every double-taking method
answers `false` without touching the device, and there is no fp64 on this hardware to fill
the gap with later. Two consequences: **the decline protocol is load-bearing in a way it
is not on CUDA** -- `linalg`'s default width is double, so on Apple the flag is inert
until a program reaches `#f` data, which `torch:` does by default and a `linalg`-only
program has to ask for -- and **`GpuTest` no longer describes both backends**: it is gated
on a double-capable device and `MetalGpuTest` answers the same claims at `#f`. Two files
rather than one width-generic suite, because the two devices do not have the same member
set, thresholds or precision story.

**Every call pushes an autorelease pool.** A command buffer, an encoder and every
`MPSMatrixDescriptor` are autoreleased; without a pool per call they accumulate for the
life of the process. `objc_autoreleasePoolPush`/`Pop` measures 0.0 us, so this costs
nothing and its absence would be a slow leak rather than a failure.

**The rank-2 product goes through MPS and the STACK does not.** Every argument that killed
cuBLAS is absent here -- MPS is in the OS, there is no f64 regression to weigh, and the two
routes agree BIT FOR BIT (0 of 703 cells on a rectangular 37x23x19 product, 0 of 262144 at
n=512), which is what lets the choice be invisible. Which route runs is a pure size
decision, `n*m*p >= 2^27` for ONE matrix. The stack stays on our kernel whatever its size,
and the reason is the ZERO STRIDE: a broadcast operand -- the rank-2 weight under a
`(B T C)` activation, which is every `torch:linear` -- passes a per-batch stride of 0, and
a batched `MPSMatrixDescriptor` cannot be handed that. What MPS CAN serve is one encode
per slab into one command buffer, and above the threshold that is what the stacked route
does: Metal's floor is per command buffer rather than per dispatch, so the encodes share
the one wait. Bit-identity also means `rowBytes` may be `columns * 4` rather than
`rowBytesFromColumns:dataType:`, which PADS (80 bytes for 19 columns) and would not
describe our contiguous row-major data.

**THE AXIS FOLD IS NOT A ROUND-TRIP MEMBER HERE, and either half of the reason would be
enough.** `%la-fold-axis` accumulates in `double` at BOTH widths, so a float accumulator
could not be bit-identical the way the broadcast and the gather are -- over a 256-long axis
the divergence would be ~1e-5 relative, not a last-ulp difference. And the amax/amin half,
which needs no accumulator and WOULD have been exact, does not pay: the CPU fold is 85 us
over 262144 f32 elements and 410 over 1048576 against this backend's ~150 and ~380. A tie
at best, and a tie is a decline. So the round-trip threshold is `Long.MAX_VALUE`, and
`mean` / `var` / `std` / `softmax` / `log-softmax` reach the device here through their
broadcast and element-wise links only -- `softmax` is 2.9x with its `amax` and its `sum`
still on the CPU, where on CUDA the same chain is 4.8x. Over a RESIDENT operand the trip is
not paid and the alternative is bringing the operand home, so `fold_f32` exists there.

**A declined call costs a little more here than on CUDA, and the reason is `worth`.** The
probe-free predicates answer with the CUDA constants, so between those and this backend's
higher thresholds an interceptor derives the broadcast strides or the permutation -- two
`int[]` -- and the library then declines anyway (~40 us on an axes transpose at the
notebook's own `(4 256 192)`, which falls just under `2^18` and is the one member whose
Apple threshold most nearly excludes the shape it was taken for). Letting `worth` consult
the threshold IN FORCE was weighed and not taken: it would make a documented,
deliberately probe-free predicate answer differently depending on whether something else
had touched the driver first, and `GpuDeclineTest` pins its answer against the constant on
every machine. **Revisit with a measurement, not with this paragraph.**

### Precision on this backend, and the three things the hardware does differently

**The strided tier's bit-identity is an ARGUMENT here rather than an inheritance.**
`gemm.cu` computes in double and narrows on the store; MSL has no double to do that with,
so `gemm.metal` computes in `float` and the claim has to be earned: `+`, `-` and `*` over
two floats are EXACT in binary64, so rounding the exact result once to float is exactly
what compute-in-double-then-narrow produces; `/` is innocuous double rounding at these
widths (53 >= 2*24 + 2, the classical bound); the strict selects and the gather move
values, so nothing rounds at all.

**Where that argument does not reach, the shader runs IEEE binary64 in SOFTWARE.** A
scalar that is not a float (`(linalg:mul g 0.1d0)`), every step of the Adam update (its
rule is ten doubles; the bias corrections are nothing a float holds) and the sum fold are
cases the float route cannot cover. So a value is its bit pattern in a `ulong`, every
operation unpacks to sign / exponent / 53-bit significand, works in a 128-bit integer so
every intermediate is exact or carries a sticky bit, and packs through ONE rounding step
(`f64_pack`: round to nearest even, the subnormals, overflow to infinity) shared by
add / sub / mul / div / sqrt and the exact widening and narrowing of a float. Division is
restoring (55 quotient bits, the remainder sticky), the square root digit-by-digit over a
128-bit radicand (56 root bits), the product four 32-bit partial products. It is slower
than a float op by a hundred-odd instructions an element, which a memory-bound launch over
a resident operand does not notice. `GpuDeclineTest` still asserts that no code line of
the file says `double`; the emulation is spelled `f64`.

**This GPU flushes subnormal floats to zero in every float operation, `MTLMathModeSafe` or
not.** Measured through the probe: a subnormal operand through `x * 7.0f`, `x > 0.0f`,
`fabs`, `sqrt` all answer as if `x` were zero, and a product landing in the subnormal
range is flushed. The CPU does neither, so every float kernel guards it (`bin_op_exact`):
an operand that is subnormal, or a result below `FLT_MIN`, is recomputed on the binary64
route, which works on the bits and never flushes; `abs` / `negative` / `sign` are bit
operations, the fold's amax / amin compare through an order key the flush cannot touch,
and `where`'s mask test is a bit test. Two compares an element. **And `sqrt` needs
`precise::sqrt`**: plain `sqrt` under the safe math mode is 1 ulp off in ~10% of operands
(27621 of 262144), where `precise::sqrt` is correctly rounded and therefore `Math.sqrt`
narrowed.

**Two transcendentals were FIXED rather than tolerated.** `tanh` and `sinh` measured
1.8e-4 and 3.1e-4 before the fix -- MSL's own carry an absolute error floor of ~3.4e-8
near zero, which is what an exp-based formula cancelling looks like, so the relative error
grows without bound as x -> 0. Both are odd with an `x + O(x^3)` expansion, so
`gemm.metal` takes the Maclaurin series to `x^9` below |x| = 1/4 (exact to ~1e-11 there)
and the builtin above it. The other ten needed nothing. **`erf` has no builtin at all** --
MSL does not define it -- so the shader runs `%la-erf-1`'s OWN series at float width,
which makes the Metal `erf` closer to the oracle than the CUDA one.

The pins: `theStridedTierIsBitIdenticalToTheScalarOracle`, and
`theSoftwareBinary64RouteLandsOnJavasDoubleArithmeticBitForBit` -- the scalar forms over
2^18 bit patterns (subnormals, the specials, the tiny and the huge) and twenty-odd scalars
from 1e-310 to `Double.MAX_VALUE`, the Adam update over three steps, the equal-shape ops
-- against Java's arithmetic, bit for bit.

### Residency and the GEMV on this backend

**The accumulator: compensated, and on the defun's bits without a `double`.** A plain
float sum lands on 229 of 1024 rows. So `gemv_f32` keeps its running sum as a float-float
PAIR: the product's rounding error recovered exactly with an fma
(`p = a*b; pe = fma(a, b, -p)`), every addition a TwoSum whose error term goes into the low
half, and the SIMD-group fold the same pair-wise. The pair carries ~48 bits against a
double's 53, and at 1024x768 over inexact data it is bit-identical to the double-accumulated
oracle on **1024 of 1024** rows, at no cost the memory-bound pass can see.
`#pragma METAL fp contract(off)` is kept: it is what makes the error-free transforms mean
what they say.

**The threshold is `2^21`, and the COLD trip never pays.** On unified memory an upload is a
memcpy of the very bytes the CPU kernel would have streamed, so "cold" is 753 us against
the CPU's 800 at the classifier head and loses everywhere -- the two-sight rule is not a
refinement on this backend but the member. The "kernel only" column IS the ~77 us floor
until the matrix is several megabytes, so the crossover against the CPU is the floor's:
1024x1024 is a tie (100 against 90 us), 1448x1448 is 2.5x, 2048x2048 4.8x, 4096x4096 9.4x.
Sixteen times the CUDA threshold, for the floor's sake.

**The idle clock is the finding that sets the ceiling on a decode loop.** The same resident
head with a CPU gap before every call, mean us per call:

| gap before the call | 0 | 100 us | 500 us | 1 ms | **2.5 ms** | 5 ms | 10 ms |
|---|---|---|---|---|---|---|---|
| 32000x288, resident | 347 | 351 | 355 | 367 | **792** | 858 | 973 |
| 1536x1536, resident | 213 | 194 | 193 | 197 | **488** | 528 | 631 |

**This GPU lowers its clocks once it has been idle for more than about a millisecond, and
the first command buffer after such a gap costs ~0.5 ms more.** A decode loop is exactly
that shape -- one GEMV per token with attention, RoPE and the sampler between them -- so
llama2 decodes at the same speed here with the flag and without it, and the per-call table
above is true and the decode loop cannot collect it. There is no public API to hold the
clocks up, and keeping the device busy on purpose would be a heater. The member is in
because it pays back to back from `2^21` by 2.5-9x; the guide says both.

**Residency: measured, and kept for ONE kind of array.** Building the full CUDA design
here made the step **slower at every cap** (1-5%), and the reason is that this platform's
economics turn around: on unified memory the upload residency removes is a memcpy, while a
slab held out of the pool costs the pool a FRESH slab for the next call of that size class
-- and a fresh slab pays its first-touch page faults, the very measurement that made the
pool mandatory. The 1 GB cap CUDA measured as neutral is the worst case here. A cap small
enough to be free would evict the one array residency is FOR on this backend. So the cache
is kept and one thing goes in it: **the matrix of an accepted GEMV** -- re-read hundreds of
times, written never, and unable to be copied per call without losing. `x` and `y` are
scratch slabs; every other member is exactly the pure pool it was. A release gives the
slabs back to the POOL, not to the device, which is the right shape for a pool that is the
whole point.

### Lazy results and the resident tier on Metal

Built here, bit-identical, pinned -- and **the interceptors do not switch it on, because it
does not pay.** The mode works: a member's result slab becomes the host array's DIRTY entry
instead of being downloaded, `materialize` is a memcpy out of the slab's `contents`, and
the lazy pool rule is its own (the POOL may hold the working set less an eighth, never less
than 512 MB, and the resident set the pool less an eighth of that, because here the pool
and the resident set compete for the same slabs). Measured: a **tie** at the notebook's
shapes (0.102 against the pure pool's 0.104 s a step, inside a 3% spread) and a **loss** at
the book's (10-19 s a step, varying, against a steady 8.9). Three things this backend does
that the GB10 does not, and together they are the answer:

1. **Every call waits.** A Metal call is `commit` + `waitUntilCompleted`; nothing overlaps.
   On CUDA the launches are asynchronous and the host's bookkeeping, allocation and
   host-side members run under them; here the step is the CPU's time PLUS the device's, so
   a member moved to the device pays in full and wins only if the device is outright faster
   at it -- and at 6-25 M elements a memory-bound launch at the ~80-150 GB/s this route
   reaches is not much faster than the M4's lane loop over the same bytes.
2. **Unified memory holds both copies.** A lazy result has a host array AND a slab; at the
   book's shapes that is a 58-60 GB pool beside a 64 GB heap on a 128 GB machine, the
   system starts compressing pages, and the device's reads of shared-storage buffers slow
   with it -- which is what the inflated, varying kernel times are.
3. **The download it saves is a memcpy** inside the same memory at 20+ GB/s. What lazy
   results removed on CUDA -- 44 GB over the link in a 200-step run -- is here about 0.1 s
   of an 8.9 s step.

So the decline is kept as a POLICY, not by tearing the mode out:
`GpuDevice.lazyResultsPay()` is the measured answer per backend,
`Gpu.lazyResultsIfWorthwhile()` is what both interceptors call, and `Gpu.lazyResults(true)`
stays the unconditional request an embedder or a test makes, honoured on both backends.
`theInterceptorsRequestLeavesResultsEagerHereAndAnEmbeddersDoesNot` pins the distinction.
**The first item above is the lever** -- committing without waiting and waiting only at the
first host touch would overlap the host with the device the way CUDA does, and it changes
when a slab may be recycled, the one ordering the residency design exists to forbid. That
is `.todo/495`, and the second item is `.todo/492`'s stubs, which are built in the library
and unmeasured here because the interceptors do not run lazily.

**The floor every resident-offered member is held to is `MIN_RESIDENT_ELEMENTS` = 2^14**,
and the training step put it LOWER than the crossover table says. A launch over resident
operands through the shipped route is ~100-140 us whatever its size until 2^18 and crosses
the CPU's memcpy-plus-lane-loop between 2^18 and 2^19 -- yet the step measured fastest at
2^14 (5.23-5.30 s over 40 steps against 5.72-5.92 at 2^18 and 5.79-5.83 with no floor),
because a declined member over a resident operand costs a materialize, the CPU loop and
the re-upload of its result around it, and a chain that flips between the two pays both
memcpys at every flip.

### The strided layout cost a pooled slab here, not a copy

The CUDA move above does not transfer its reasoning: `uploadLayout` wrote the ints straight
into a shared slab's `contents()`, a memcpy on unified memory that orders behind nothing.
What the layout cost here was **one pooled slab acquired and released per strided call**
(`MIN_SLAB_BYTES` is 4096, so a 96-to-256-byte layout took a 4 KB slab), its
`setBuffer:offset:atIndex:` binding, and -- the reason it matters beyond the allocation --
a pooled buffer that a committed command buffer reads, which is a slab a future per-slab
fence would have to cover. Counted before it was moved, per training step: **724 layout
slabs of 4444 pool acquisitions at the book's shapes, 55 of 346 at the notebook's width**
(381 `bcast` and 343 `gather` launches a step at the book's; `where` and `copy_strided` are
zero at both, because they are resident-only members and this backend is eager). After:
3720 and 291, exactly 724 and 55 fewer. The layout now rides by
`setBytes:length:atIndex:` at the index the buffer was bound at -- a `constant int* meta
[[buffer(N)]]` parameter takes either -- and `Gpu.MAX_STRIDED_RANK` is what keeps the
length under `setBytes`'s 4 KB limit (four vectors at rank 16 is 256 bytes; the packer
guards it and throws into the member's own `catch`, which is an ordinary decline).

**It is worth no measurable time, and the COUNT is why it was worth doing.** Per call
through the shipped route (`MtlStridedFloor.java`, medians of three, us) `bcast` goes 242
-> 245 at 262272 output elements, 354 -> 341 at 1048704 and 1884 -> 1884 at 8388864, and
`gather` 196 -> 199, 346 -> 339, 1834 -> 1793; a strided `copy` over a RESIDENT operand --
the one strided shape that runs below the size threshold -- is unmoved at every size from
2^14 to 2^20, under the ~110-130 us command-buffer floor either way; and the step is
0.106 -> 0.107 at the notebook's width and 8.50 -> 8.57 at the book's, both inside a 2%
spread. Noise everywhere, and it has to be: the smallest call the tier takes here is a
memory pass over 2^18 elements and what was removed is a deque pop, a binding and a push.
Every output is byte-identical between the two builds, which is the gate the change was
accepted on.

## The interception layer

The flag over the same `linalg:` seam `--simd` opened and `--blas` widened. Read
`.kb/linalg-simd.md` for the declined-input protocol (the null sentinel, the captured
binding, `LispEvaluator.applyGlobal`) and `.kb/linalg-blas.md` for the flag whose shape
this copies verbatim -- **only what is DIFFERENT about a GPU is written here.**

| backend | interceptor | kernels |
|---|---|---|
| interpreter (`prog.lisp --gpu`, native binary included) | `eval/LinalgGpu` (re-`defineFunction`) | `eval/LinalgGpuKernels` -> `am.ik.gpu` |
| JVM (`-o Prog.class --gpu`) | `codegen/jvm/JvmLinalgKernelCompiler` (call site) | `JvmGpuTemplate` -> the EMBEDDED `am.ik.gpu` |
| wasm-GC / `--no-gc` | out of scope, no FFM -- a hard error | -- |

**A `.wasm` output REFUSES rather than ignores** (`RontoLispCli.compileRecorded`, beside
the `--blas` guard): silently running unaccelerated is exactly what an acceleration flag
exists to make visible.

**`--gpu` is value-less and `RontoLispCli.enableGpu` is `enableBlas` one layer up.**
**Nothing may ask `LinalgGpu.available()` on a path that did not pass the flag** -- it runs
the probe, which is a `dlopen`, a `cuInit`, a retained context and a PTX JIT (~26 ms cold),
or on a Mac `MTLCreateSystemDefaultDevice` plus an MSL compile. That is the one way this
flag is not like `--blas`, whose availability check is nearly free.

### The intercepted set

**Forty-four `linalg:` members and one outside it.** By round trip: `linalg:dot` over two
packed rank-2 operands of the same width (hence `matmul` at rank 2 and `solve`
transitively); `%la-matmul-nd`, the STACKED product behind `matmul` at rank >= 3; the
twelve element-wise `exp` `log` `tanh` `sin` `cos` `tan` `asin` `acos` `atan` `sinh` `cosh`
`erf`; the STRIDED tier -- `add` `sub` `mul` `div` `maximum` `minimum` at a BROADCAST shape
only, `sum` `amax` `amin` in their `:axis` form only, `transpose` in its axes form only;
and `%la-rng-fill`, the seeded generator's fill behind `rand` / `randn` / `uniform`, the
only member with NO operand. Over a RESIDENT operand: the resident, index and copy tiers
listed under residency. Outside `linalg:`: `vec:matvec`, installed by `LinalgGpu.installVec`
from the VEC library's own lazy-load hook, because the two libraries load independently and
a program may reach either first.

**Nothing else is `defineFunction`ed**, and that is an assertion rather than a remark:
`#'linalg:outer`, `#'linalg:norm`, `#'linalg:matmul`, `#'linalg:reshape`, `#'linalg:softmax`
and nine more still print `#<lambda>` under the flag, which is each tier's own dead-flag
guard from the other side.

The set is narrower than `--blas`'s in one direction and wider in the other, and both
differences are measurements: the gemv shapes are here only over a resident matrix where
`--blas` takes them outright; the STACKED product is here where `--blas` stopped at `dot`,
because on a device a batch axis is `blockIdx.z` and it is a transformer's whole hot path;
the transcendentals are here and are not a product at all; the strided tier is here at ONE
call shape per member and declined at the others.

**The generator fill is the one member whose device result is byte-for-byte the CPU's at
every size**, and that is what let it in: the closed form `a^k s mod m` lets thread `i`
jump to its own state by square-and-multiply (exact integers), then draw exactly as the
sequential walk does -- the same divides, the same left-associated sum, the same
frac-by-compares -- every arithmetic step an `_rn` intrinsic so nvcc cannot contract the
`lo + span * u` of `uniform` into an FMA, and `Gpu.rngAdvance` advances the END state on
the host by the same closed form. `linalg:seed`'s promise is what made bit-identity the
price of admission.

### The chain order, and why the device goes on top

On the interpreter a chain is INSTALL ORDER -- each `install` captures whatever the symbol
was bound to and declines back to it -- so where `LinalgGpu.install` sits in
`LispEvaluator.resolveFunction`'s lazy-load hook IS the decision. It goes LAST:

```
--gpu --blas --simd  ->  device -> library gemm -> lane kernel -> scalar linalg.lisp defun
```

and every prefix works the same way. Three reasons, in the order they bind: **`worth()` is
probe-free and three orders of magnitude above `--blas`'s** (2^17 against 64), so the
device turns down everything small before anything touches the driver and being on top
costs a declined call nothing -- underneath `--blas` it would never SEE a product, since
the library accepts from 4x4x4 up; **where it accepts it is at worst level with a threaded
CPU BLAS and clearly ahead at f32**; and **a declined member then lands on the best CPU
path the invocation asked for**, never back on the scalar defun. The last is pinned with
`.kb/linalg-simd.md`'s own f32 v.M probe, whose two spellings make the fallback target
legible from Lisp (the defun prints 16778240, the lane kernel 16777216 or 16777984
depending on the machine -- so the target is READ from an unflagged run rather than written
down).

**The wart, measured and accepted:** at n=64-96 with `--gpu --blas` both on, the device
accepts a product a 20-core OpenBLAS would have finished sooner (139 us against 21 at n=64,
f64). `worth()` is calibrated against `--simd`, which is what a machine without a tuned
library has, and it cannot be calibrated against `--blas` without `am.ik.gpu` learning
whether a CBLAS is loaded -- which would make a language-independent library depend on one.
**On Metal the same wart runs from the threshold to about n=1500**, because Accelerate's
f32 gemm holds 2.1 TFLOP/s from n=1024 (the CPU cluster's matrix coprocessor, not lanes or
threads) and pays no per-command-buffer floor, while the device's efficiency climbs with n
as the fixed floor and copies are amortized. Same argument, same decision.

**On the NATIVE BINARY the wart is much wider and the reason is not the interceptor's.**
One n=512 f64 product: `--gpu` 18500 us against the JVM's 735, `--blas` 7800 against 1160.
The BLAS half is explicable (single-threaded there); the GPU half is 25-60x with no
threading involved, so the FFM downcall path in the image is the suspect and neither
`am.ik.gpu` nor `eval/LinalgGpu` changes between the two. **Nothing may quote a device
figure from a native-image INTERPRETER run without measuring it first**; the workaround is
the one the flag wants anyway -- compile the program, and the class the native binary emits
is byte-for-byte the class `java -jar` emits and runs at the JVM figures.

**`--parallel` sits strictly BELOW the device decision on both backends**: the device
attempt runs on the calling thread (so does `DeviceResidency`, which is not thread-safe),
and only what it declines reaches the row-parallel lane kernel.

### The call site

`JvmLinalgKernelCompiler.compile` emits up to THREE attempts, in the interpreter's install
order:

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

**The device attempt is per MEMBER, not one hardcoded method.** `JvmLinalgGpu.kernelKey`
maps each member to its `ops` key, where the key IS the bridge method name, so no third
table sits between them. **The extended (option-form) call sites carry a device rung too**
-- the axis folds and the axes transpose are device members ONLY in that shape -- claimed
when EITHER bridge has a kernel for it and emitted with the SAME `LinalgKernelCallLayout`
the lane attempt uses, so again no second table. A call shape at which nothing would be
attempted routes to `compileDefault` rather than emitting an empty chain, which is what a
`--gpu`-only build reaching `(linalg:sum a)` does.

**The emit gate is `programUsesSymbol` over EVERY member**, not `--blas`'s gate on `dot`
alone: a transformer reaches only the stacked member and the ufuncs, and a gate on `dot`
would embed no bridge for exactly the program this flag is for. A program that reaches no
member embeds nothing, and **`--gpu` must NOT drag in the `--simd` bridge** -- a class that
did would need `java --add-modules jdk.incubator.vector` to run.

### `-Pweb`

`LinalgGpu.available` / `description` / `install` / `installVec` are the only entry points
into `LinalgGpuKernels`, which holds the only reference to `am.ik.gpu` from `eval` -- so
BOTH bindings drop out behind the same four substitutions
(`src/web/java/.../Target_LinalgGpu.java`). **A new public method on `LinalgGpu` that
touches the kernels would break it, and only the Pages workflow's Web Image build would
notice**; `./mvnw -Pweb compile` is the local check. `codegen.jvm`'s reference does not
matter: those classes are read as RESOURCES, never linked.

## The JVM backend: the whole library travels in the class

The decision phase 2 existed to make, and it went the OTHER way from `--blas`'s. `--blas`
embeds one flat template class that is a hand-kept COPY of its kernels; a GPU binding is
~1700 lines across several classes plus two kernel texts, and the parts a copy would fork
are exactly the parts that were expensive to get right -- the decline that must cost the
device nothing, the 101-entry status table and which seventeen are sticky, the per-device
safepoint threshold, the chunked critical copies. Two hand-synced copies of THAT is a
standing bug.

So `JvmGpuRuntimeBuilder` generalizes the template mechanism from one class to a CLOSURE of
them plus data resources:

- every class file of `am.ik.gpu` is renamed by ONE prefix rule, `am/ik/gpu/` -> the
  generated program's own package plus `RontoLispGpu`, so `Gpu` becomes `RontoLispGpuGpu`
  and a nested class follows its outer without being named. A class emitted into
  `com/example/` gets `com/example/RontoLispGpuGpu`, because `Lookup.defineClass(byte[])`
  requires the defined class to share the lookup class's package.
- `JvmGpuTemplate` -- the call site's glue -- is renamed to `RontoLispGpuBridge` by the
  same pass, which is what lets it be WRITTEN against `am.ik.gpu` and type-checked by
  javac while resolving to the embedded copies at run time.
- each is base64'd into its own chunked string constant and `_gpuInit` runs one
  `defineClass` per blob. Definition order is free: a class file's references to its
  siblings resolve lazily.

**BOTH kernel texts travel in every `--gpu` class whichever machine emitted it**, because
the machine that compiles a program is not the machine that runs it and a standalone class
that accelerated only on its birthplace would not be one. They cannot be resources on the
other side -- renamed into a program's own package there is no such resource and there
never can be -- so `_gpuInit` hands each to `Gpu.useKernels` / `useMetalKernels` before
anything can probe. Those two public methods are the entire cost this route imposed on the
language-independent library, and they are a legitimate embedder API rather than a
rontolisp hook.

**The size objection does not survive measurement.** `am.ik.gpu`'s class files are 118 KB,
the bridge 13, the PTX 113 and the MSL 9.4, so a `--gpu --simd` class is ~300 KB bigger
than a `--simd` one -- against the 62 KB `JvmSimdVectorTemplate` every `linalg` program
under `--simd` already embeds. **If the blob ever has to shrink, `sin` / `cos` / `tan` are
the place**: they are 38 KB of PTX for three members (a Payne-Hanek argument-reduction
table) where `exp` is 2.9 and `erf` 4.5. They were kept because the RULE is the measurement
-- each is 9-22x on the device -- and dropping them would be a size decision overriding a
speed one.

Two routes were weighed and rejected, and the reasons are worth keeping: **a `--gpu`-only
support jar** makes `-o Prog.class --gpu` non-standalone, a real departure since every
other flag emits a class that runs with a bare `java Prog`; and **a thin template that
reaches `am.ik.gpu` reflectively when the jar happens to be on the classpath** is a SILENT
degradation of a kind this feature does not otherwise have -- "no device" declining quietly
is a property of the MACHINE, which the flag reports on stderr, while "you forgot a jar" is
a property of the INVOCATION, and an acceleration flag exists to make exactly that visible.

**What it is NOT.** The renamed classes are defined into the emitted class's own loader, so
two `--gpu` classes loaded by ONE classloader would collide on `defineClass` -- the same
property the `--simd` and `--blas` bridges have, and the reason the compiled-backend tests
give each program a fresh `URLClassLoader`. Each such loader also probes and JIT-loads the
module again; a real program has one.

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
| the flag is value-less, the REPL pair, the `.wasm` refusal | `cli/CliOptionsTest`, `cli/RontoLispCliTest` |

**The dead-flag guard is the load-bearing one**, as it is for `--blas`: every numeric
assertion would pass just as well on the scalar defun, so `#'linalg:dot` printing
`#<function LINALG:DOT>` under the flag and `#<lambda>` without it is the assertion that
fails when the flag is DEAD. It is now twenty-four assertions, one per member, plus the
complementary list of members that must still be `#<lambda>`. On the compiled side the
guard is the bridge NAME in the class bytes (the renamed library classes are base64 and do
not appear as text; the kernel texts do, which is what pins that they travel).

Six things worth knowing before editing these:

- **`GpuDeclineTest` is the half a CI runner actually runs** -- every machine this project
  has is GPU-less -- so it is the half that must never regress. It pins that the probe
  answers without throwing, that every decline condition declines rather than throws, that
  the status table is total and only the context-destroying statuses are sticky, that the
  PTX is the artifact the loader expects with its regeneration command still attached, that
  the checked-in MSL names its kernels and holds no `double` outside comments, that the
  op-code mirrors match on both sides, and -- the one that matters -- **that an op code the
  library does not name DECLINES rather than quietly computing something**.
- **That last test hands the library the REAL checked-in text and no test anywhere may hand
  it anything else**: the override is process-wide and read at probe time, so a placeholder
  would decide what the whole suite's device compiles, whichever class ran first.
- **`JvmGpuRuntimeBuilder.embeddedGpuClasses()` is pinned against the class files the build
  actually produced** -- the guard that a class added to `am.ik.gpu` is added to the list
  that travels, since nothing can enumerate a package from a classpath, let alone from
  inside a native image.
- **The interceptor suites derive their shapes and their width from the device in force**,
  through the test-scope `am.ik.gpu.GpuThresholds` shim: `SIDE` is the smallest accepted
  square (64 on CUDA, 208 on Metal), `MAP_N` twice the element threshold, `TYPE` is
  `single-float` where the device has no double. A hard-coded 64 would have made every
  accepted-product assertion vacuous on the second backend.
- **The six tests that assert on FREE DEVICE MEMORY hold a `@ResourceLock`** and their
  bound is 1.5 GB, because `cuMemGetInfo` reports the DEVICE and the JVM fork loads a
  separate copy of the binding per compiled class. Every leak run is sized so a real leak is
  2-8x the bound.
- **Exact-input operands must be exact IN THE FOLD too** -- a 64-long sum of products of
  1..4096 is not, at f32, because the defun accumulates in f64 and no f32 kernel can follow.
  That is `.kb/linalg-simd.md`'s reduction contract and not this seam.

The claims each suite states as assertions rather than trusting: a batch is bit-identical
to the same slabs run one at a time; every element-wise op against `java.lang.Math` over
its own domain (the assertion that catches a mis-numbered op code, which nothing else can);
each strided member against a Java oracle written out longhand; the generator fill against
the sequential walk at both widths and all three rules, and `rngAdvance` against a
100,000-step walk; the two residency enumerations; the resident and index tiers against the
CPU kernels' bits and their declines without a resident operand; the clip norm's CLOSENESS
and reproducibility; the strided tier's byte-identity on every machine; and, on the JVM,
`aLazyResultAllocatesNoHostArrayOnTheCompiledBackend`, which runs the class in a CHILD JVM
with a 256 MB heap holding forty-eight 16 MB results -- which fits only if none has a host
array -- and runs the same program without the flag under the same heap to see it die of
`OutOfMemoryError`, so the bound has teeth.

## Native image

Two build inputs, both in
`src/main/resources/META-INF/native-image/am.ik.rontolisp/rontolisp/`:

- **`resource-config.json`**: `gemm.ptx` and `gemm.metal`, each TWICE -- conditional on the
  device class, and again conditional on `JvmGpuRuntimeBuilder`, because a binary that only
  ever COMPILES never makes `CudaGemm` reachable -- plus `JvmGpuTemplate.class` and
  `am/ik/gpu/.*\.class`, the CLASS FILES the compiler reads as resources to embed them.
- **`reachability-metadata.json`**: a `foreign.downcalls` entry per distinct SIGNATURE --
  45 across both drivers and `--blas`, including the two-`MTLSize`-by-value entry for
  `dispatchThreadgroups:threadsPerThreadgroup:`. **Without them the linker REFUSES the
  handle at BINDING time, not at call time**, and both drivers bind every entry point in
  their constructor -- so one missing shape fails the whole binding and the binary reports
  "no driver" ON A MACHINE WITH A WORKING GPU and runs unaccelerated.
  That is how one round shipped. Two things stand against it now: the drivers answer `null`
  only when the LIBRARY is absent and let a binding failure THROW, so the probe prints the
  reason instead of blaming the machine; and `NativeImageForeignConfigTest` binds both
  drivers against a lookup that finds everything -- no device needed, so it runs on every
  machine -- and asserts every shape they ask the linker for has an entry.

Generate them with the tracing agent over a program that opens the binding and runs a
member, then fold the result in; the agent traces `Linker.downcallHandle`, so merely
constructing a driver registers every shape. **The type names must be the agent's own**
(`jlong`, `jint`, `jboolean`): the un-prefixed aliases parse, but `boolean` does NOT, so one
spelling throughout is what keeps a re-run's diff empty. **A per-entry `"comment"` key is
rejected by the schema**, which is why the signature-to-entry-point mapping lives in the
file's top-level `comment` array.

Verified: a `--no-fallback` image built with the real binary's flags loads the checked-in
PTX, runs both kernels, takes the multi-chunk copy route at n=3072 and prints exactly what
the JVM prints. So CUDA does not re-enter todo-102's `VectorAPISupport` /
`SharedArenaSupport` fight; nothing here needs `Arena.ofShared`.

## What is deliberately NOT here

Each is a measured decline, not an omission, and each needs this file's numbers before it
is revisited.

- **No element-wise member whose scalar cost is one machine instruction, AS A ROUND TRIP**
  -- `sqrt`, `abs`, `negative`, `sign` and the binary `add` / `sub` / `mul` / `div` at an
  equal shape. They are members over a RESIDENT operand, which is not a reversal: it is the
  case the refusal's measurement never had, a launch with no copy. Re-run
  `ElementwiseCrossover.java` plus `elementwise-baseline.lisp` before offering any of them
  as a round trip again.
- **No axis fold on METAL as a round trip**, with two numbers attached. The amax/amin half
  is the one to revisit first if that backend's floor ever drops; the sum half cannot come
  back at all while `%la-fold-axis` accumulates in double.
- **No lazy results on METAL for the interceptors**, and so no index tier or clip norm
  there. Built, pinned, measured a tie and a loss; `.todo/495` is the lever and the
  measurement list.
- **No fused softmax / layer-norm / dropout / GELU kernels.** Measured rather than assumed:
  the LAUNCH half of what fusion buys vanished when the pipeline opened, and the MEMORY
  half is ~100-150 ms of a 700 ms step across the whole fusible set. Backward fusion means
  hand-written adjoints in `torch.lisp`. `.todo/499`.
- **Nothing of `vec:` but `vec:matvec`.** `vec:matvec-into` writes a CALLER's array, which
  the device would have to download into and the caller's next write invalidate; `vec:dot`
  is one reduction over two vectors the device would have to be handed, and loses to the
  lane kernel at every size. The first sight of a big matrix used ONCE is also left on the
  table deliberately (16 MB cold would have won 2.7x): a program that runs one GEMV does
  not care.
- **No zero-copy route**, and no staged UPLOAD -- both measured, both above. Measure with
  FRESH arrays before touching either half again.
- **The per-call cost of an FFM downcall inside a native image is still unexplained**, and
  the generic `MethodHandle` invoker under every downcall (the driver's handles are instance
  fields and therefore not constants to the JIT) is `.todo/476`.
- **No per-device collection policy.** The shared `DeviceResidency` request is never made on
  Metal today, so there is nothing to decide; it becomes a `GpuDevice` question the moment
  lazy results pay there.
