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
// the FUSED tier (todo-499): one pass each where torch.lisp composed a chain of members
static boolean  gelu(a, oA, out, oOut, n) / geluGrad(g, oG, x, oX, old?, oOld, out, oOut, n)
static boolean  softmax(a, oA, out, oOut, rows, len) / softmaxGrad(g, oG, s, oS, out, oOut, rows, len)
static boolean  layerNorm(x, oX, out, oOut, rows, len, eps) / layerNormGrad(g, oG, x, oX, old?, oOld, out, oOut, rows, len, eps)
static boolean  dropoutMask(out, oOut, n, p, span, s1, s2, s3)          // the inverted-dropout mask, from the generator state
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
  never enters and the one that was wrong.
  `aDeclinedProductCostsTheDeviceNothing` still asks `cuMemGetInfo`, which reports the
  DEVICE, not the process -- unavoidable there, since a refused allocation is the pool
  failing to grow in the first place, which has no pool-local counter to ask instead.
- **The five `...FreesEveryBufferItAllocates` leak tests measure the POOL, not the
  device** (`CudaGemm.poolBytesInUse`, `GpuTest.driftSample`; .todo/481). They used to
  compare `cuMemGetInfo` before and after a 1000-call loop, two-sided (free memory that
  GREW would mean the test was measuring the rest of the machine) and deliberately loose
  (1.5 GB, `GpuTest.DRIFT_BOUND`) because `cuMemGetInfo` reports the whole DEVICE: the
  JVM backend's fork defines a separate copy of the binding per compiled class (measured
  drift with the strided tier's tests in the set: 808 MB), and on a unified-memory
  machine (the GB10) it is the HOST's free memory too, which a sibling process --
  another surefire fork, or anything else on the machine -- moves just by running.
  Seen in a full `./mvnw test` on the GB10: 1.78-1.85 GB of drift against the 1.5 GB
  bound, close enough to this suite's own real-leak sizes (as low as 1.2 GB for a single
  buffer) that widening the bound further would have started hiding real leaks instead of
  tolerating noise. `CU_MEMPOOL_ATTR_USED_MEM_CURRENT` is scoped to the pool HANDLE it is
  asked of, which this process created and no other process can allocate from, so it
  answers only for what this device object itself has outstanding -- measured immune to
  an unrelated process actively touching 8 GB of host memory throughout the run, where
  `cuMemGetInfo` drifted 1.3 GB and the pool's own count did not move. `GpuTest.driftBound`
  falls back to `cuMemGetInfo` and the old loose bound only for a driver with no pool.
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

## The accept rules against the shapes the programs run (todo-655, 2026-09-03)

Two items two days apart were the same defect -- an accept rule and the shapes actually
flowing through it had never been put side by side. `.todo/495` was the test side (a fused
shape chosen from the FOLD threshold, so on Metal it collapsed and the tier under test never
ran) and `.todo/650` the production side (a `(batch 1 key)` mask against a rule that wants a
trailing suffix, 96 of 144 heads declining). This is the sweep neither did, and it was split by
hardware: **the PRODUCTION side is below**; the test side -- whether `GpuTest`,
`LinalgGpuTest`, `MetalGpuTest` and `LinalgGpuDeclineTest` actually run the tier each one
names -- needs a Metal machine to see the `.todo/495` form at all (that backend's fold
threshold is `Long.MAX_VALUE`, so a shape chosen from it collapses), and was taken by the
Apple-silicon session in the same round -- "The test-side sweep on Metal", below.

**How it was measured.** A counting hook on `LinalgGpu.define` -- the one place every
interpreter offer passes through -- keyed by the member's NAME and by the ARGUMENT SHAPES as
the rule sees them (width, dims, and RESIDENCY read before the kernel runs), tallying accept
against decline. Per-step numbers are one run diffed against a shorter one, so setup
cancels. The hook was a temporary edit and is not in the tree, as `.todo/650`'s `StackWalker`
was.

**Why the interpreter answers for both offer layers.** `eval/LinalgGpu` and
`codegen/jvm/JvmGpuTemplate` are two copies of one decision ("The offer is decided twice",
below) that `GpuOfferDifferentialTest` pins to agree, and the SHAPES are the program's, not
the backend's. The one thing that used to differ -- when a host read is answered -- is what
`.todo/650` removed. Where a price was taken it was taken on the compiled path anyway.

### What each program hands each member

`--gpu --simd`, GB10, per step where the program has steps.

**The chapter-2 Transformer** (`transformer-book-shapes.lisp`, `d_model` 512, 6 blocks, 8
heads, batch 64, vocabulary 6638): about 4,100 offers a step, **212 of them declines**, and
191 of those are one rule.

| declines a step | member | the shape | the rule that refused it |
|---|---|---|---|
| 96 + 95 | `%la-scaled-masked-softmax` and its grad | `f(64 18 19)` / `f(64 19 19)` score with an `f(64 1 19)` mask | `suffixLength` -- `.todo/650`, priced at 0.8% and left alone |
| 2 | `linalg:add` | RESIDENT `f(64 19 512)` + `d(1 19 512)` | same width -- the example's DOUBLE `pe` buffer, priced below |
| 2 | `%la-gather-strided` | `d(1 20 512)` | not resident (the same buffer) |
| 6 | `linalg:reshape` | `f(64 18)`, `f(64 19)`, `d(18 18)` | not resident -- host-built masks |
| 3 | `linalg:equal` | array with a scalar, not resident | the resident tier |
| 2 | `linalg:div` | two NUMBERS | not an array at all |
| 4 | `where` / `sub` / `add` | `(1152)`, not resident | the resident tier |

**The chapter-3 GPT** (`gpt-book-shapes-fast.lisp`, 13.06 M parameters, batch 64, block 256,
6 layers): **12 declines a step**, none of them more than 6 calls and none over an operand
bigger than 65536 elements -- six `reshape`s of a host mask, two `linalg:div` on two numbers,
two `reshape`s of an `f(64 256)`, one `negative` and one `add` over a non-resident `(16384)`.
Its `(1 256 256)` mask IS a suffix once the leading extent-1 axis is dropped, so nothing in
the attention declines. **It is also the one program here that calls
`torch:clip-grad-norm`, and the clip norm reaches the device on every call**: 634 offers of
`%la-sum-squares` and `%la-scale` a step, **634 accepted and 0 declined**, every operand
resident because the optimizer updates the parameters there and the gradients arrive from
device members. That answers a hypothesis raised from the test side in the same round --
both members are resident-only, and the tests hand them FRESH operands, so the tier they
name never runs there. The tests were wrong about the shape; the programs are not. Chapter 2
never calls `torch:clip-grad-norm` at all and llama2 is inference, so the GPT is the whole
production population for that path. **And that mask is a `double[]` over a `float` score, accepted only
because the device contract takes a mask of either width** -- the exact clause `.todo/645`
found `MetalGemm` narrowing. It is the only production shape in this repository that
exercises it, which is worth knowing before anyone narrows that parameter again.

**llama2** (`stories15M.bin`, `dim` 288, 6 layers, 6 heads, 60 greedy tokens): every offer is
`vec:matvec`, and the census splits three ways.

| calls a 60-token run | shape | outcome |
|---|---|---|
| 348 x 3 + 58 + 6 | `f(768 288)`, `f(288 768)`, `f(32000 288)` | ACCEPTED, matrix resident |
| 12 + 6 + 1 | the same three | declined on FIRST sight -- the two-sight residency rule, by design |
| **1440** | **`f(288 288)`** -- the four attention projections, 24 a token | declined on SIZE: 82944 against `MATVEC_POOLED_MIN_ELEMENTS` 2^17 |
| 2160 + 2160 | `f(48 256)`, `f(256 48)` | declined on size, 12288 elements -- the per-head attention math |

`examples/ml/tiny-llm.lisp` is the same story one size down: 96 declines at `f(256 256)`
(65536) and 60 more below that, against 62 accepts at `f(512 256)`.
`examples/ml/gpu-matmul.lisp` declines nothing.
`examples/deep-learning-from-scratch` ch05 and ch07 are `#d` toys (batch 16 and 10) whose
declines are all sub-threshold folds and non-resident operands -- except one, which is the
only refusal in this whole census with a price.

**Everything not named in the two ceilings below is free by arithmetic**: at most a handful
of calls a step over operands of a few hundred to a few thousand elements, against a step of
0.42 s (chapter 2) or a token of 2 ms (llama2). A decline whose operand is smaller than one
device round trip's floor cannot cost anything, and none of them is.

### Ceiling 1: llama2's `288x288` GEMV against the 2^17 threshold -- worth nothing

The constant's own javadoc puts the crossover "between 256x256 (10.0 us CPU against 9.7, a
tie at `#f`) and 384x384 (23.0 against 10.7, 2.1x)" and sits it at the second, "where the win
is unambiguous". llama2's shape is 288x288, INSIDE that band, and it is 1440 of the run's
~4,900 offers. So the size rule and the workload's shape are 1.6x apart and nobody had put
them together.

The ceiling was taken by lowering `MATVEC_POOLED_MIN_ELEMENTS` to 2^16 -- both arms built
from one tree, one constant apart -- and confirmed structurally first: the census flips from
1440 declines to 1392 resident accepts, so the arm does what it is meant to. Compiled
`-o Llama2.class`, 256 greedy tokens, the story byte-identical either way:

| | 2^17 (today) | 2^16 (the ceiling) |
|---|---|---|
| tok/s, median of 5 | **460.4** (426.6-477.3) | **463.3** (426.6-466.2) |
| tok/s under nsys | 455.7 | 425.0 |
| `gemv_f32` launches | 4,199 | **9,503** |
| device kernel time | 67.2 ms | **87.7 ms** |
| `cuMemcpyDtoH` | 4,196 | **9,500** |
| `cuMemcpyHtoD` | 2,892 | **5,568** |
| `cuCtxSynchronize` | 2,878 | **5,542** |

**5,304 extra round trips and 20 ms more device time a run, for a wall inside the noise.**
Every accepted GEMV pays a download because a decode loop reads `y` on the host immediately;
that is why the shape is refused and why the threshold stays where it is. The javadoc's
"tie" is exactly right, and a tie is what the first sentence of "Every threshold" refuses.

### Ceiling 2: chapter 2's DOUBLE `pe` buffer -- accepting it is a LOSS

`transformer/utils.lisp` keeps the sinusoidal positional encoding at `double` on purpose
(`chapter02/section3.lisp` asserts a 1e-6 bound that `f32` cannot hold) and its comment names
the consequence: "adding this buffer to single-float activations is a MIXED-width pair, which
--simd declines". What it does not say is what that costs under `--gpu`, and this file's own
copy table attributes 2 of the step's 4 remaining downloads (4.85 MB) to it. That reads like
money on the table.

**It is not. Removing those two downloads makes the step slower.** The ceiling is `PEF=1` in
`transformer-book-shapes.lisp`, which rewrites the two `:pe` buffers to single float through
`torch:set-field` after the model is built -- a cheat the example could not ship, and done
through the FIELD rather than by redefining the builder because a compiled program binds a
`defun` at compile time. Compiled `-o Tf.class`, `--gpu --simd`, one class serving both arms,
per step from `STEPS=23` diffed against `STEPS=3` (medians of 3):

| per step | `PEF` off (today) | `PEF=1` (the ceiling) |
|---|---|---|
| `cuMemcpyDtoH` | 4.0 | **2.0** |
| `cuMemcpyHtoD` | 14.0 | 14.0 |
| `cuCtxSynchronize` | 12.0 | 12.0 |
| `cuLaunchKernel` | 6963 | 6965 |
| device kernel time | 292.2 ms | 296.6 ms |
| **wall** | **0.413 s** | **0.449 s (+8.8%)** |

The `STEPS=13` pair agrees in direction and reads +19%. The interpreter census confirms the
arm changed exactly two decisions a step and nothing else. **The device does the same work
(launches and kernel time flat to 1.5%), the last two downloads a step are gone, and the step
loses 9-19% of its wall in HOST time** -- the accepted add leaves a 2.4 MB result on the
device every step instead of producing a host array, and the resident tier pays for holding
it. The mechanism was not chased further because the direction settles the question: **the
mixed-width decline the example chooses is a saving, not a cost.** The rule stays, the
example's `double` buffer stays, and the 2 downloads stay.

Note what this is an instance of. `.kb/measurement-probes.md`'s rule 2 says price the
CEILING before building; here the ceiling of the obvious change is NEGATIVE, and the copy
profile that suggested the change ("2 of the 4 downloads are this") is exactly the evidence
that would have got it built. **A copy count is not a cost until someone removes the copies
and times it.**

### Ceiling 3: a `-1` reshape extent -- built (todo-663, 2026-09-03)

`linalg:reshape`'s defun documents the NumPy spelling ("One extent may be -1 and is inferred
from the element count") and both offer layers used to refuse it: `LinalgSimd.shape` and
`JvmGpuTemplate.shapeOf` each answered `null` for a negative extent. And `reshape` is a
resident-tier member, so the decline dragged a resident array home for the defun to copy
element by element.

`examples/deep-learning-from-scratch/ch07/train-convnet.lisp` is the program that writes it:
im2col reshapes with `(list -1 ...)`, 80 declines a run over resident `#d` arrays up to
432000 elements. **Built**: `LinalgSimd.reshapeShape` (interpreter, shared by `--simd` and
`--gpu`) and `JvmGpuTemplate.reshapeShapeOf` (the compiled bridge) each resolve one `-1`
extent against the operand's element count -- `linalg::%la-infer-shape`'s own rule, mirrored
rather than shared, since neither package may import the other -- and decline exactly where
the defun would signal (a second `-1`, or a known-extent product that does not divide the
total evenly). Every other reader of `shape` / `shapeOf` (`gather-strided`'s `od`,
`col2im`'s `dims`, `transpose`'s axis list, `dropout-mask`'s allocation shape) is untouched.
`GpuOfferDifferentialTest`'s boundary table carries the `-1` spelling at both ends: a bare
flatten and an inferred trailing extent (accepted), a repeated `-1` and a non-dividing one
(declined) -- the two offer layers cannot drift apart on this shape either.

Re-measured on this build, same program, three walls an arm (interleaved, `--gpu --simd`,
the accuracy line identical to the ceiling's):

| | declined (pre-fix) | resolved (built) |
|---|---|---|
| compiled, `-o Cv.class` | 1.71 / **1.76** / 1.81 s (median of 8) | 1.53 / **1.56** / 1.62 s (median of 8) (**-11.4%**) |
| interpreter | 22.04 / **22.36** / 22.37 s | 13.79 / **13.99** / 14.07 s (**-37.4%**) |

A structural count (`nsys profile -t cuda`, whole run) agrees on the mechanism: the resolved
arm issues 34 fewer `cuMemcpyDtoH` and 44 fewer `cuMemcpyHtoD` than the declined arm (125/150
against 159/194), which is the 80 avoided round trips net of the ones im2col's OTHER reshapes
(the non-`-1` ones) already paid for regardless.

**This does not reproduce the filed ceiling's -17.1% on the compiled path -- the interpreter
side reproduces in the same direction but OVERSHOOTS it (-37.4% against -25.6%).** Both
"declined" columns land inside the filed ceiling's own range (1.71-1.81 against 1.76-1.83;
22.04-22.37 against 22.10-22.28), so the DECLINE arm has not moved; the resolved compiled arm
is the one that reads slower here (1.53-1.62 against the filed 1.47-1.52) while the resolved
interpreter arm reads faster (13.79-14.07 against 16.45-16.65).

**The two deviations point OPPOSITE ways, and only one of them has an explanation here.** The
compiled path's shortfall is what a fixed startup cost does: the whole run is ~1.5-1.8 s end to
end, JVM start and CUDA context init are a large and machine-load-sensitive fraction of it, and
a fixed cost on both arms can only dilute a ratio. But dilution cannot make a ratio LARGER, so
it cannot be why the interpreter reads -37.4% against a filed -25.6%; and at a twenty-second
floor the same fixed cost is about two percent of the run, with no room to move a ratio twelve
points either way. **The interpreter's overshoot is unexplained and was not chased.** What would
settle it is the filed run's own conditions -- rounds, warmup, which step differences were
taken -- which the filing did not record (`measurement-probes.md`, rule 1: a number whose
conditions are not written down can be neither trusted nor dismissed later); the other candidate
is that something landing between the filing and this build moved the RESOLVED arm alone, the
decline arm having been checked against its filed range above.

**The conclusion does not rest on either wall number.** The structural counts are an independent
observation of the same change -- 34 fewer `cuMemcpyDtoH` and 44 fewer `cuMemcpyHtoD`, exactly
where 80 avoided reshapes predict -- so the mechanism holds whatever the percentages do, and the
census that filed this item never depended on the exact figure. That is why the discrepancy is
recorded rather than chased onto a second machine.

### The device contract, read against the CUDA implementation

`.todo/645` was a third shape of the same defect: `GpuDevice.whereF`'s javadoc promised a
mask "of either width" and `MetalGemm` took `float` only, so a shape the contract admits was
declined by one backend. Every clause of `GpuDevice`'s javadoc that an implementation could
silently narrow was therefore read against `CudaGemm`:

| clause | `CudaGemm` |
|---|---|
| `where` / `whereF`: mask is a `double[]` or `float[]` of either width, or `null` | both (`mkind` 1 and 2) |
| `where` / `whereF`: any of `m` / `x` / `y` may be a scalar | all three independently nullable |
| `softmax` / `softmaxF` / their grads: mask of either width or `null` | both, and a non-array mask is an explicit decline |
| `gemmT` / `gemmFT`: a backend with no transposed kernel may refuse any non-plain orientation | takes all four orientations |
| `take`: `mode` 0 is take-rows, 1 is gather | both, the mode rides in the parameter block |
| `gemv`: offered only once the matrix has been seen twice unwritten | `offeredBefore`, both widths |
| `sumSquares`: `null` on a decline | yes |
| the fused tier: present on CUDA | all eighteen |
| "no method here throws and none signals" | every kernel entry is `try { ... } catch (Throwable) { return false; }` |

**No narrowing.** Every shape-level accept rule on this backend lives in `Gpu`, above the
device; `CudaGemm` adds only `usable` and allocation failure.

**`MetalGemm` was read against the same clauses on 2026-09-03, and narrows nothing either.**
Its softmax family takes a mask of either width -- `.todo/643` built it that way from the
start, because the pack kernel reads the mask as a raw word (one for `f32`, two for `f64`,
low half first) and so needs an integer test rather than the `fp64` arithmetic this backend
does not have; `.todo/645` later applied that same shape to `whereF`. `gemmFT` passes `ta`
and `tb` through to the dispatch and refuses no orientation, so both backends are WIDER than
the clause allows and the production accept rate is the same on each. The one robustness
note below is CUDA's alone: `MetalGemm.whereF` and its `softmaxScaledMasked` write the
non-array mask as the same explicit refusal, so there is no pair of spellings there. **The
contract-versus-implementation sweep is therefore closed on both backends with `.todo/645`
as its only ever finding** -- recorded so the next reader does not repeat the comparison.

**And no PRODUCTION code does arithmetic on a threshold.** That question is worth asking
separately, because Metal's fold threshold is `Long.MAX_VALUE` and `2 * Long.MAX_VALUE`
wraps NEGATIVE -- which is how `GpuOfferDifferentialTest` came to run every one of its
operands at 1024 elements on that backend. Every site in the repository that multiplies,
adds to, or takes a root of a threshold is in a TEST (`LinalgGpuTest`,
`JvmLinalgGpuAccelCompilerTest`, `GpuOfferDifferentialTest`); `Gpu`'s own use of them is
`>=` and nothing else, so a `Long.MAX_VALUE` threshold declines rather than wrapping. **A
threshold is safe to compare against and unsafe to compute from**, and only the tests
compute from them. One robustness note, not a decline any shape in any program has ever
reached: `CudaGemm.where` used to map a non-null mask that is neither `float[]` nor
`double[]` to `mkind` 0, the SCALAR-mask path, so such a call would have computed rather than
declined -- `softmaxKernel` wrote the same test as an explicit refusal, which meant two
spellings of one guard, one of which failed open. Closed (todo-663, 2026-09-03): `where` now
takes the same explicit refusal `MetalGemm.whereF` already wrote, so both spellings are a
decline.

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

**The one reader that cannot be enumerated** is `am.ik.rontolisp.runtime.RontoFloatArray`,
the `rontolisp:jvm-export` handle: it is a class OUTSIDE the generated program, so it
adopts the generated class instead and resolves that class's `_gpuMaterialize` /
`_gpuWritten` reflectively, reading and writing what the guard answers
([jvm-export.md](jvm-export.md), "The packed float array"). It is also why the boundary
does NOT materialize when it hands a result over -- doing so would download a result the
next call would only re-upload.

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

**The tier survives the Java boundary, measured.** A compiled library's
`:float-vector` result crosses as a `RontoFloatArray` handle wrapped WITHOUT materializing
([jvm-export.md](jvm-export.md)), so the question this file has to answer is whether a
Java-side chain still keeps its intermediates here. It does: 200 chained GEMVs over a
resident 2048x2048 f32 matrix run at **0.070 ms/iteration through the handle against
0.070 for the same chain inside Lisp**, with **1 upload for the whole run** where a
materializing boundary pays 200 (8 KB against 1600 KB) and 0.098-0.117 ms/iteration. The
read at the end brings the result home exactly once -- one dirty copy cleared, one stub
given a backing -- and answers the same library built without `--gpu` bit for bit; a
`set` through the handle invalidates the resident copy the way the emitted `_gpuWritten`
guard does, so the next call sees it. `examples/jvm/bench/`, `./run.sh gpu`, GB10 +
GraalVM 25.0.4, CUDA. **On Metal the same three say the claim is true and idle** -- see
"Lazy results and the resident tier on Metal" below.

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
there is no policy to decide while the request is never made.

**Re-taken 2026-09-02, when todo-495 made lazy results pay here and the request started
firing** (23 steps at the book's shapes and 200 at the notebook's width, `-Xmx24g`, two
rounds each, the asynchronous build with the heap-aware budgets of "Asynchronous command
buffers on Metal"):

| flags | 23 steps, book's | `Pause Full` | total pause | 200 steps, notebook's |
|---|---|---|---|---|
| **default collector (G1)** | **44.4 / 48.5 s** | 12 / 11 | 0.16 s | 9.44 / 9.44 s |
| ... plus `-XX:+ExplicitGCInvokesConcurrent` | 45.0 / 48.1 s | 0 | 0.10 s | 9.48 / 9.40 |
| ... plus `-XX:+DisableExplicitGC` | 49.7 / 50.0 s | 0 | 0.25-0.28 s | 9.49 / 9.37 |
| ... plus `-XX:+AlwaysPreTouch` (`-Xmx32g`) | 51.7 / 48.5 s | 16 / 14 | 0.42-0.57 s | 9.37 / 9.35 |
| `-XX:+UseParallelGC -Xmn8g` | 51.4 / 48.7 s | 12 | 0.5 s | 10.05 / 10.0 |
| ... plus `-XX:+ExplicitGCInvokesConcurrent` | 50.2 / 48.2 s | 12 | 0.5 s | 10.06 / 10.0 |
| ... plus `-XX:+DisableExplicitGC` | 51.3 / 50.8 s | 2-3 | 1.1-1.2 s | 9.96 / 10.0 |
| `-XX:+UseParallelGC`, young adaptive | 49.0 / 48.6 s | 6-7 | 0.75-0.82 s | 9.90 / 9.9 |

The sentence stands and its reason changed: the request IS made now (the twelve full
collections of the default row are the library's, 7 ms each -- the heap holds stubs and
backings, not the activations), and the default collector answers it best or tied.
`-XX:+DisableExplicitGC` costs 4-10% at the book's shapes now, where it cost nothing, for
CUDA's reason in miniature: refused, the LRU evicts dirty entries as flushes into fresh
backings. `-XX:+ExplicitGCInvokesConcurrent` is a tie, so nothing needs saying about it.
The parallel rows are 0-15% slower at the book's and 6% at the notebook's, and the pages
argument still has nothing to act on. What `-Xmx` decides on a Mac is now TWO things: the
heap, and the pool the device's results live in, which is sized off the working set less
the heap.

## The CUDA backend

**Fifty-two entry points in `gemm.cu`** (eleven in `gemm.metal`, which has no f64
sibling, no generator and no fused tier), each taking its member as an op-code PARAMETER: the products
(`gemm_f64/f32`, the batched pair, and two register-tiled f32 siblings), the element-wise
`map_f64/f32`, the strided `bcast_*` / `gather_*` / `fold_*`, the generator `rng_fill_*`,
the GEMV `gemv_*`, the resident tier's `zip_*` / `scal_*` / `where_*` / `adam_*` /
`copy_*` / `take_*` / `scatter_*` / `sumsq_*`, and the fused tier's `gelu_*` /
`gelu_grad_*` / `softmax_*` / `softmax_grad_*` / `log_softmax_*` / `log_softmax_grad_*` /
`layer_norm_*` / `layer_norm_grad_*` / `dropout_mask_*` ("The fused tier", below). A
batched kernel
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
was estimated at 100-150 ms of a 700 ms step and filed as `.todo/499`; measured per
member it was closer to 260 ms, and it is built -- "The fused tier", below. The
end-to-end figures live in the guide and in `examples/llm-from-scratch/README.md`.

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

### The chapter-2 step re-measured, and the reduction adjoint that stopped mattering (todo-500, 2026-09-02)

`.todo/500` was filed off the 2026-08-24 chapter-2 profile (in this file's git history,
commit `13a52ba`): 104 `cuMemcpyHtoD` a batch of which **90 uploads, 183 MB, were
`torch::%t-grad-bcast`** staging `(linalg:add (linalg:zeros-like x) gk)` -- an array of
zeros allocated only to be broadcast over. **It is already gone, and the fused tier is
what removed it.** Those 90 were the 30 `torch:layer-norm` modules' three reduction nodes
each (the mean, the variance's mean and the variance's sum); todo-499 replaced the
composition with `%la-layer-norm` and todo-634 with `%la-layer-norm-affine`, so the module
carries no `torch:mean`/`torch:sum` node at all. Counted in the adjoint itself over three
book-shape steps, `%t-grad-bcast` now runs **ONCE a step**, over the cross-entropy's
flattened `(1280)` per-position loss, with a SCALAR gradient -- a 5 KB allocation, and it
appears nowhere in a copy trace. **The item's premise is dead; there was no code change
to make.**

**The `-0.0` question it left open is moot, and was measured rather than reasoned.** The
replacement would have been bit-identical except at `-0.0`, where `0.0 + v` answers
`+0.0` and a strided copy would not. Instrumented (`(= v 0.0)` and `(< (/ 1.0 v) 0)`, so
`-0.0` and no other value) over three book-shape steps, **zero `-0.0` elements reach
`%t-grad-bcast`** -- nor the two scalar fills of the same shape, `%t-unbroadcast`'s
number branch and `%t-grad-reshape`'s, which are never reached at all. The normalization
`.kb/torch.md` records therefore stands untested by any program here, and stays as it is.
Note also that `%la-layer-norm-grad` MIRRORS the old spelling on purpose ("every line is
the adjoint torch.lisp spells for that op, the broadcast onto zeros included"), so
changing the adjoint alone would have broken that member-for-member pin for no measured
gain.

**The step, re-profiled.** `d_model` 512, 6 blocks, 8 heads, `d_ff` 512, batch 64,
`max_length` 20, vocabulary 6638 -- the book's shapes, over a SYNTHETIC corpus of
10-19-token sentences rather than `small_parallel_enja` (the 2026-08-24 row is the real
corpus, so the walls are not strictly comparable; the counts are structural). `--gpu
--simd`, JVM class output, GB10, `java -Xmx64g -XX:+ExplicitGCInvokesConcurrent`, nsys
over a 33-step run diffed against a 13-step one:

| per step | 2026-08-24 | now |
|---|---|---|
| wall | 0.317 s | **0.36 s** |
| device kernel time | 230 ms (73% busy) | **291 ms (~81% busy)** |
| `cuLaunchKernel` | 11029 | **6794** |
| `cuCtxSynchronize` | 102 | **204** |
| `cuMemcpyHtoD` | 104 copies, 247 MB | **302 copies, 88.7 MB** |
| `cuMemcpyDtoH` | 4 copies, 4.3 MB | **292 copies, 30.8 MB** |

The launches fell by a third (the fused tier) and the uploaded bytes by two thirds (the
zeros), but the COPY COUNT rose, and the downloads by 73x. A stack-walking trace on
`upload`/`download` (the same scratch method as 2026-08-24) attributes every one:

| per step | copies | MB | caller |
|---|---|---|---|
| **288 up + 288 down** | 288/288 | **25.9 each way** | **the attention softmax pair round-tripping a declined fused member** -- below, and `.todo/650` |
| 1 up | 1 | 30.6 | `%la-log-softmax-grad` staging the `(1280 6638)` gradient |
| 2 up (+2 int) | 2 | 27.2 | `%la-scatter-rows`, the embedding table's gradient |
| 2 up | 2 | 4.85 | `%la-matmul-nd` staging the activation the DOUBLE `pe` buffer was added to |
| 2 down | 2 | 4.85 | `torch:add` in `positional-encoding-forward` -- the mixed-width decline the example chooses |
| 2 up | 2 | 0.009 | a `zip` broadcast |
| 2 int up | 2 | 0.009 | `take-rows`, the embedding forward's index vector |
| 1 up | 1 | 0.083 | `%la-scaled-masked-softmax`, the one head shape that does NOT decline |
| 1 int up, 1 down | 2 | ~0 | `torch:gather`'s index vector and the loss scalar in `%m-ce-hard` |
| 1 up | 1 | 0.005 | `linalg:where` inside `%la-scaled-masked-softmax-grad` |
| 1 down | 1 | ~0 | one `aref` of the loss |

So **the 14 uploads that are not the round trip are the same nine the 2026-08-24 trace
named**, minus the zeros; and the FOUR downloads that profile counted are still exactly
four (two positional-encoding, the loss scalar, one `aref`). Everything else is new.

**Where the new traffic comes from: a `(batch 1 length)` mask.** `torch:padding-mask` puts
a query axis of extent 1 in so it broadcasts over a `(batch query key)` score, and
`LinalgGpu.suffixLength` requires the mask to be a trailing SUFFIX of the score's dims --
`(batch 1 key)` against `(batch query key)` fails on the middle axis. So the encoder's 48
self-attention heads and the decoder's 48 cross-attention heads decline
`%la-scaled-masked-softmax`, the compiled fallback materializes the 90 KB score
(`64 x 19 x 19` f32) to run the defun, and the defun's own `linalg:softmax` uploads it
straight back: 96 round trips forward, 96 more backward with two operands each. The
decoder's 48 SELF-attention heads carry `padding + subsequent`, a `(batch length length)`
mask, which is a suffix -- they take the fused member and copy nothing. Chapter 3's GPT is
unaffected for the same reason: its mask is `(1 T T)`, whose leading extent-1 axis is
dropped before the suffix test. Filed as `.todo/650`, and **the round trip was not the
decline: it was the guard in front of it** -- next section.

### The decline that was only a materialize (todo-650, 2026-09-03)

The 288 round trips came from ONE emitted instruction pair, and removing them needed no
change to what the device accepts. `JvmLinalgKernelCompiler` emits the `linalg:` call site
as a chain -- device attempt, then the `--blas` rung, then the `--simd` lane rung, then the
scalar defun -- and between the device attempt and the host rungs it emitted
`_gpuMaterialize` over EVERY argument. That guard exists for the LANE and LIBRARY kernels:
they read raw storage past every access hook, so they have to be handed bytes that are
home. **The defun does not need it.** It is compiled Lisp, and reports each of its own
reads and writes through the same guards -- `.kb/gpu.md`, "The two seams", is what makes
that true.

A device-only member has no lane kernel at ANY flag: the fused tier is `--gpu` and nothing
else. So for `%la-scaled-masked-softmax` the chain was *device attempt -> materialize five
arguments -> defun*, and the materialize dragged home a 90 KB score whose FIRST reader is
`linalg:div` on the device, which staged it straight back. The guard is now emitted only
where a host KERNEL rung follows it (`simd != null || (blas != null && !extendedCall)`);
the write report for an in-place member is emitted whatever follows, because that one is
about correctness. A `--gpu`-only build now carries no guard at any `linalg:` call site at
all, for the same reason.

The guard is the compile path's alone -- the interpreter never had it, measured below --
so this is a `-o out.class` / `-o app.jar` finding and the numbers here are compiled ones.
It is BACKEND-independent: the same emitted chain runs over Metal, whose own A/B of this
round trip (879 -> 687 downloads a step, 194 -> 178 MB, todo-645's follow-up) was taken on
JVM class output and is therefore this same guard, not a Metal one.

**Measured on the shared probe**, `.todo/123-gpu-acceleration/transformer-book-shapes.lisp`
at the book's shapes, `--gpu --simd`, JVM class output, GB10, `STEPS=13` diffed against
`STEPS=3`, `nsys --trace=cuda`. `WIDEN=1` is the probe's A/B: it gives the source mask the
score's own shape so every head is ACCEPTED, which is the CEILING a widened `suffixLength`
could reach.

| per step | declined + guard (before) | ACCEPTED (`WIDEN=1`, the ceiling) | declined, no guard (now) |
|---|---|---|---|
| `cuMemcpyDtoH` | 292 copies, 30.8 MB | 4 copies, 4.86 MB | **4 copies, 4.86 MB** |
| `cuMemcpyHtoD` | 302 copies, 88.7 MB | 15 copies, 63.0 MB | **14 copies, 62.8 MB** |
| `cuCtxSynchronize` | 204 | 13 | **12** |
| `cuLaunchKernel` | 6771 | 6771 | **6963** |
| device kernel time | 294.7 ms | 294.2 ms | **292.0 ms** |
| 13-step wall (median of 3) | 8.29 s | 7.70 s | **7.70 s** |
| wall a step | 0.491 s | 0.408 s | **0.416 s** |
| device busy | 60% | 72% | **70%** |

**The fix lands exactly on the acceptance ceiling.** The four downloads left are the four
the 2026-08-24 profile counted, and the fourteen uploads are the fourteen this file's table
above already named one by one; the round trip is gone entirely. What the decline still
costs is **192 extra launches a step** -- each of the 96 declined chains runs `div`, `where`
and `softmax` as three device passes instead of one -- and that is not measurable: kernel
time and wall are the accepted column's, inside the noise. Widening `suffixLength` on top
of this was measured at `WIDEN=1` against the fixed build and is worth 0.06 s over 13 steps,
0.8%, inside a +-4% band. **So the mask rule stays as it is** -- and with it
`JvmGpuTemplate.softmaxMaskLength`, its word-for-word twin on the compile path, and both
backends' `mask[i % maskLen]` kernels, which a widened rule would have had to grow a stride
for.

**The INTERPRETER was never affected, and that was measured, not reasoned.** The same
probe on `java -jar` (`--gpu --simd`, `STEPS=3` diffed against `STEPS=1`) costs **4
downloads and 14 uploads a step, 12 `cuCtxSynchronize`, 6963 launches** -- BEFORE this
change as after it, byte for byte the fixed compiled column above, and nothing like the
292/302/204 the compiled output was paying beside it. So for two weeks
`java -jar prog.lisp --gpu` issued strictly less traffic on this program than
`-o prog.class` did, and every `--gpu` copy profile taken on the compiled backend carried
288 copies the interpreter did not. The interpreter is ~4.5 s a step here against the
compiled 0.42 (the evaluator, not the device: its kernel time is the same 292 ms), so the
asymmetry never showed up in a wall.

**That asymmetry is not the duplication `.todo/654` is about.** The two paths did not
disagree about a RULE -- `suffixLength` and `softmaxMaskLength` both declined this mask,
identically. They differed in when a host read is answered: the interpreter installs
`FloatArrayAccessHook` and materializes LAZILY, at the read, through the record's `data()`
accessor, while its interceptor hands the device `storage()`, which reports nothing -- so a
declined member applies the captured binding over an operand that is still resident. The
compile path had no such accessor to hook for a lane kernel that takes a `double[]`
straight, so it materialized EAGERLY, in front of every host rung, and then in front of a
rung that was not a lane kernel at all. Two designs for the same guarantee, one of which
had a case it did not need. It is now the same rule on both.

**The wall here is a forced measurement, not an enqueue one.** Every step ends in
`torch:item` on the loss, which is a host read of a device result: the step cannot finish
without a download and a synchronize, so nothing hides behind the launch queue. **And a
variant with that read removed -- the loss returned unread, `torch:item` only on the last
step -- profiles IDENTICALLY**: 292/302/204 before and 4/14/12 after, the same counts to
the copy, because the step's own optimizer and reductions synchronize whatever the caller
does. Only the wall moves, and it moves inside the noise band (before: 0.491 s a step
forced against 0.451 unforced; after: 0.416 against 0.421). **So on this program there is
no enqueue-vs-work ambiguity to fall into** -- which is worth knowing before designing a
probe for it. That distinction is the one Metal's probes had to learn the hard way
(todo-643/645, this file's Metal half); on this backend, at this shape, it does not bite.

**The same shape of finding as todo-641, one layer down.** There the upper layer's decision
was identical on both backends and the LOWER layer's capability differed, so the same symptom
cost -2.3% on CUDA and 15% on Metal ("The attention scale and mask", above). Here the
upper layer's decision -- `suffixLength`, which is above `GpuDevice` and therefore identical
on both backends -- was ALSO not the thing to change: the cost was a guard the JVM backend
emitted around the decline, which is why Metal's 192 declined downloads a step are the SAME
192 and go with this change rather than needing a Metal one. **Read a decline's price
before reading its rule.** That is now three items where the rule looked wrong and the layer
below it was where the money was.

## The fused tier (todo-499, 2026-09-02; todo-629 added two members, todo-641 two more, todo-634 two more)

The four compositions a transformer step spent a third of its device time on -- the exact
GELU, softmax, layer-norm's normalization and the dropout mask, forward and backward --
each as ONE kernel where `torch.lisp` launched a chain of `linalg:` members, one memory
pass per member. **What a fused kernel buys on this card is only the passes it removes**:
the launches were already pipelined (above), so a chain of five bandwidth-bound passes
over a 100 MB activation was five times the traffic of one.

**The contract is the chain's, rounding for rounding.** A `linalg:` member computes in
double and stores at the width, so a chain rounds at every member boundary; each fused
kernel reproduces every one of those roundings (the `(T)` casts in `gemm.cu`), keeps
every axis fold ascending and sequential in a double accumulator, and evaluates `exp` /
`erf` at the width as `map_op` does. So a fused member lands on the bits the chain of
DEVICE members would have produced -- `GpuTest.theFusedTierLandsOnTheComposedDeviceChainsBitsAtBothWidths`
runs every chain member by member and fused and asserts equality, libm members included --
and the three with no libm in them (softmax's adjoint, layer-norm and its adjoint, the
mask) land on the CPU defun's bits (`theLibmFreeFusedMembersAreTheSequentialReferencesBits`,
a sequential Java replay; `LinalgGpuDeclineTest` on every machine). The GELU and softmax
forwards stand to the CPU exactly as an accelerated `erf` or `exp` does; the GELU adjoint,
with both libm calls and a cancelling sum of two branches, measures 1.8e-12 relative at
`#d` over a ramp and is pinned at 1e-9.

**The members are the compositions, on every backend.** `.kb/torch.md` "The fused
compositions" has the tape-order argument; here the consequence: the seven internal
`linalg` defuns (`.kb/linalg.md`) ARE the chains, so nothing moved on any CPU path, and
`torch:gelu` / `torch:layer-norm` / `torch:softmax` / `torch:dropout` print the same bits
as the compositions they replaced (`TorchGradcheck.FUSED_PROGRAM` on the three test
backends, `ci-spec.yaml`'s `torch-fused-compositions` on all four). The two adjoints that
fold onto an accumulated gradient take it as an operand (`OLD`, a null pointer when there
is none) so the kernel adds it exactly where the tape's `%t-accum` would have.

**The offer rule is the chain's.** A fused member with a libm call in it (GELU, its
adjoint, softmax) is offered from the map threshold or over a resident operand, like a
transcendental; the libm-free ones from the fold threshold or over a resident operand,
with the fold's cell rule on the row count, like an axis fold; the mask exactly as
`rngFill`. An operand that is not the one the rule is about -- the attention mask, and
layer-norm's `(len)` weight and bias -- is bounds-checked and staged, and does not decide. `linalg:softmax` and `linalg:log-softmax` are intercepted in their `:axis` form
over the LAST axis only -- any other axis, the whole-array form, a scalar input decline to
the defun, whose members the device then takes one by one as before -- and on the JVM that
form is an EXTENDED call shape (`LinalgKernelCallLayout`) the way `sum :axis` is.

### Three things measured on the way

- **A thread-per-row kernel over global memory LOSES to the chain it replaces.** The
  first softmax gave one thread one row and walked it three times (the fold kernel's own
  pattern): 0.79 ms a call at `(64 256 256)` against the five-member chain's 0.68, because
  thirty-two threads reading thirty-two rows read addresses a row apart and WRITING that
  way touches thirty-two lines per warp store. The row kernels now stream the rows through
  a transposed shared-memory tile, thirty-two columns at a time (`gemm.cu`, "THE ROW
  KERNELS' LAYOUT"): the thread still owns its row -- a sequential double fold has no
  lane-parallel form that keeps its bits, and the fp64 pipe is used fully only when
  thirty-two rows advance in one warp instruction -- but every global access is a
  coalesced column read or write. `ROW_WARPS` (2) is the block: two tiles of `32 x 33`
  doubles per warp under the 48 KB static limit, and `CudaGemm.ROW_BLOCK` launches at
  exactly `ROW_WARPS * 32` threads, since the kernel derives its rows from that. Softmax
  0.79 -> 0.27 ms.
- **nvcc contracts `a * b + c` into an FMA wherever the operands are doubles**, and at
  f64 every `(T)` boundary is a no-op, so `acc += dev * dev` rounded once where the chain
  rounds twice: one ulp in a layer-norm row and in a softmax adjoint, caught at `#d` by
  the chain comparison (the `#f` kernels were exact, the float cast between the product
  and the sum blocks the contraction). The `__dmul_rn` family never contracts and was the
  first fix -- and it DOUBLED the fused kernels (`gelu_grad` 1.7 -> 3.5 ms a call at
  `(32 256 1536)`), because the intrinsics also switch off everything else the compiler
  does with a double expression. **The file is now compiled with `-fmad=false`** (the
  header's regeneration command carries it), the two products that should fuse -- the
  16x16 `gemm<T>` and the GEMV -- say `fma()` explicitly (the tiled GEMMs always did), so
  every documented fold is unchanged, and the fused tier is plain operators again.
- **The generator's jump is two hops.** `rng_fill` computed `a^(i * draws) mod m` per
  element by square-and-multiply over the full exponent (~23 iterations, three moduli);
  it now takes the block's part once per block (one thread, shared) and each thread its
  offset inside the block (twelve bits at most). Exact integer arithmetic either way, so
  the sequence is byte-identical (`theGeneratorFillIsBitIdenticalToTheSequentialWalk...`
  is the pin), and a `(64 256 384)` fill is 1.42 -> 1.02 ms: the fill is compute-bound,
  and the mask kernel inherits the same two hops.

### The measurements

Kernel time per call, `fusion-baseline.lisp` + `fusion-segments.py` at the book's shapes,
before and after (the torch rows are forward + backward through the tape; the
"input alone" baseline of ~0.3 / 0.45 / 1.9 / 0.5 ms is in both columns):

| composition, shape | before (chain) | after (fused) | |
|---|---|---|---|
| `linalg:softmax :axis -1`, `(64 256 256)` | 0.679 ms, 5 launches | 0.271 ms, 1 | 2.5x |
| `torch:softmax` forward + backward | 1.642 ms, 12.6 | 0.937 ms, 5.6 | |
| `torch:layer-norm` forward (no grad), `(64 256 384)` | 2.062 ms, 14 | 0.838 ms, 3 | 2.5x |
| `torch:layer-norm` forward + backward | 8.631 ms, 48.6 | 2.899 ms, 14.6 | 3.0x |
| `torch:gelu` forward (no grad), `(64 256 1536)` | 5.485 ms, 5 | 1.198 ms, 1 | 4.6x |
| `torch:gelu` forward + backward | 19.123 ms, 18.2 | 5.894 ms, 5.2 | 3.2x |
| `torch:dropout` forward (no grad), `(64 256 384)` | 2.573 ms, 4 | 2.029 ms, 2 | the fill is compute-bound |
| `torch:dropout` forward + backward | 3.345 ms, 8 | 2.782 ms, 6 | |

**The step.** `gpt-book-shapes-fast.lisp` at BATCH 32 -- the machine's memory was shared
with a 93 GB process that day, and the step's graph does not fit beside it at 64; the
kernels are bandwidth-bound, so the step scales with the batch -- 10 steps isolated as
`(t13 - t3)`, nsys kernel time bucketed by kernel and grid:

| | before | after |
|---|---|---|
| kernel time a step | 356 ms in 3774 launches | **272 ms** in 2964 launches |
| of which the products | 121 ms | 121 ms |
| of which everything else | 234 ms | **151 ms** |
| wall a step, `(t13 - t3) / 10` | 0.41 s | **0.31 s** |
| the four compositions, forward + backward | GELU ~50, layer-norm ~31, softmax ~28, dropout ~13 | GELU 13.5, layer-norm 10.2, softmax 10.7, dropout 11.3 |

Doubled to the book's batch that is roughly 300 ms of elementwise work a step against
PyTorch's 133 (its dropout, softmax, layer-norm and GELU are one kernel each too, and
that is now true here); what remained was the linear backward's transposes (121 + 180 + 6
`gather` launches, 24 ms at batch 32 -- now built, "The transposed product" below),
`.todo/629` (the chains left composed -- measured member by member and half of it built,
"The chains left composed" below) and `.todo/500` (the reduction adjoint's zero upload).
The loss series of the fused run is the unfused run's to the
printed digits at every step, as the bit-identity above says it must be.

## The transposed product (2026-09-02)

`torch.lisp`'s two matmul adjoints -- `g . b^T` and `a^T . g` -- used to reach the product
through a TRANSPOSED COPY of the operand, so that the stacked kernel could read a
contiguous slab. At the book's shapes that copy was the largest element-wise cost left
after the fused tier: measured at batch 64, 343 `gather_f32` launches and **53.5 ms a
step**, over four grid shapes (the activation `(64 256 384)` 121 a step, the per-head
`(64 256 64)` 180, the attention score `(64 256 256)` 36, the feed-forward
`(64 256 1536)` 6), plus 127 small `copy_f32` launches a step for the rank-2 weight
transposes.

**The kernel reads the operand where it lies.** `gemm<T>` and `gemm_tiled<T, TM, TN>` take
two flags, `ta` and `tb`: an operand so marked has its `M x K` (or `K x N`) matrix STORED
`K x M` (or `N x K`), and the staging load indexes it that way. The TILE the fold reads is
the same tile either way, so every cell still folds `k` ascending through one `fma()` per
term and **the product is bit-identical to the plain product of the transposed copy** --
`GpuTest.aTransposedOperandIsReadInPlaceAndFoldsOntoTheUntransposedProductAtBothWidths`
asserts equality, not a tolerance, at both widths and at shapes reaching all three tiles.
The per-batch stride is the operand's OWN either way: a transposed slab holds the same
`n * m` elements, so nothing above the kernel has to know which orientation it carries.

**What changes is the load PATTERN, and the staging swaps its thread indices to keep it
coalesced.** A transposed operand is walked down its columns, so the 16x16 kernel loads
`As[tx][ty]` (and `Bs[tx][ty]`) from the storage's own row-major order -- shared tiles
padded to `TILE + 1` so the transposed store is conflict-free -- and the register-tiled
staging loops run `m` (or `k`) innermost instead of `k` (or `n`). Padding the REGISTER
tiles the same way was measured and LOST: `_t4` 954 -> 934 ms but `_t8` 2322 -> 2376 over
the 13-step profile, net worse, so they stay unpadded.

**The seam is two members, not a flag.** `linalg::%la-matmul-nd-ta` (`a^T . b`) and
`-tb` (`a . b^T`), each arity 2, each intercepted exactly where `%la-matmul-nd` already is
-- the interpreter's `LinalgGpu`, the JVM bridge's `gpuMatmulNdTa` / `gpuMatmulNdTb`. Two
members rather than one taking two booleans because that keeps every existing call-shape
lowering: a member with flag arguments would have needed a new extended call shape on the
JVM backend for nothing. The portable defuns are the transpose and the product they name,
so `--simd`, `--blas`, both WASM backends and the plain interpreter are untouched, and a
decline lands on exactly what ran before. **Metal carries them too** since todo-631, on
the same two flags and on MPS besides -- "The transposed product on Metal" below.

**Measured, batch 64** (`gpt-book-shapes-fast.lisp`, `--gpu --simd`, JVM class output, GB10
with the machine to itself; the step is `(t23 - t3) / 20`, median of three interleaved
rounds; the kernel columns are nsys over a 13-step run). NOTE these are BATCH 64 numbers --
the fused-tier table above is batch 32 and the two must not be mixed:

| | before | after |
|---|---|---|
| `gather_f32` | 4459 launches, 695.0 ms | **936, 26.1 ms** |
| `copy_f32` | 2639 launches, 101.3 ms | **988, 90.2 ms** |
| `gemm_batched_f32_t4` | 877.3 ms | 953.9 ms |
| `gemm_batched_f32_t8` | 2378.9 ms | 2322.1 ms |
| total kernel time | 7187 ms (553 a step) | **6541 ms (503 a step)** |
| wall a step | 0.686 s | **0.639 s** |

The three buckets the item named are gone; the 72 launches a step that remained (2.0 ms)
were the ATTENTION HEAD's own `(torch:transpose key '(0 2 1))` and its adjoint, a `torch:`
tape node in the model rather than a matmul adjoint -- removed by making the transpose a
VIEW the tape carries, "The attention head's transpose" below. The transposed `_t4` costs
about 9% more than the untransposed one at these shapes, which is 5.9 ms a step against
the 51.5 the gathers gave back. **The loss series is byte-identical to the previous
build's at every step**, as the bit-identity above says it must be.

**One number to carry forward: a single run does not show this.** The first before/after
pair measured (t13 - t3) / 10 once each and reported 0.737 vs 0.736 -- no change -- while
the profile said 50 ms a step of kernel time had gone. The program varies about 15% run to
run on this machine; three interleaved rounds over 20 steps found the 7%. Measure this
program the long way or do not measure it.

## The attention head's transpose (todo-630, 2026-09-02)

What "The transposed product" could not reach: `(torch:matmul query (torch:transpose key
'(0 2 1)))` is the MODEL's code, in PyTorch's own idiom, and `torch:transpose` was an eager
tape node -- `linalg:transpose` wrote the swapped copy, its adjoint wrote a second one, 72
`gather_f32` launches at the per-head `(64 256 64)` grid a step. **The fix is the one
PyTorch has: the transpose is a VIEW the tape carries** (`.kb/torch.md`, "The transpose
view"): `torch:transpose` of the last two axes returns a tensor whose data is a marker
naming the source, `torch:matmul` reads the marker and calls `%la-matmul-nd-tb` /
`-ta` over the source where it lies, and it records the SOURCE as the parent, computing
its gradient straight in the source's orientation -- `(a^T . g)^T` is `g^T . a`, the same
products folded in the same `k` order, so the bits are the eager node's. Nothing in
`am.ik.gpu` changed; the device members are the ones todo-628 built.

**Measured, batch 64** (`gpt-book-shapes-fast.lisp`, `--gpu --simd`, JVM class output, GB10
with the machine to itself; the step is `(t23 - t3) / 20`, median of three interleaved
rounds; the kernel columns are nsys over a 13-step run). BATCH 64 numbers, like the two
tables above and unlike the fused tier's:

| | before | after |
|---|---|---|
| `gather_f32` at `(64 256 64)` (grid 4096) | 72 launches a step, 2.00 ms | **0** |
| `gemm_batched_f32_t4` for the key's gradient | grid `(4 1 64)`, 36 a step, 5.00 ms | grid `(1 4 64)`, 36 a step, 4.40 ms |
| total kernel time a step | 480.3 ms in 2589 launches | **478.9 ms in 2517** |
| wall a step, the three rounds | 0.632 / 0.608 / 0.661 | **0.602 / 0.602 / 0.605** |
| wall a step, median | 0.632 s | **0.602 s** |

The gather bucket is gone; the key's gradient product moved from the `(d s)` orientation
to the `(s d)` one (the same 36 launches, 0.6 ms cheaper). The kernels gave back 1.4 ms and
the wall about 30 -- the rest is what 72 fresh 4 MB device results a step, and the 36 held
across the forward, cost the allocator and the collector, the same shape as the six logits
launches in the section below. Note the AFTER rounds sit within 0.5% of each other where
the BEFORE rounds span the usual 9%; a single pair would have shown anything from 0 to 9.
**The loss series is byte-identical to the previous build's at every step of all six runs
and both profiles.** The rank-2 `copy_f32` at grid 4096 (72 a step, 2.9 ms) that remains
at the head shape is `torch:cat`'s slice adjoint over the six heads, not a transpose.

The view mechanism is what the attention SCALE and MASK, the two eager nodes between this
product and the fused softmax, were then built on: the next section.

## The attention scale and mask (todo-641, 2026-09-02)

The two eager nodes between the transposed product above and the fused softmax:
`(torch:div score (sqrt d-k))` and `(torch:masked-fill score mask -inf)`, 72 `scal_f32` and
72 `where_f32` launches a step at the `(64 256 256)` score, 7.9 + 7.8 ms, each a full pass
over a 16.8 MB slab the softmax was about to read anyway. **Both are VIEWS now**
(`.kb/torch.md`, "The views": the tensor record's `:scale` and `:fill` kinds), and
`torch:softmax` in its `:axis` form consumes the chain as ONE node --
`linalg::%la-scaled-masked-softmax (x scale mask fill ax)` forward, its `-grad` adjoint,
the tape edge routed past the views to the score -- which on this backend is the
`softmax_*` / `softmax_grad_*` pair with the scale and the mask folded in: each cell read
as `(T)(x / s)` (the exact-reciprocal multiply where `Gpu.scale` would use it) and then as
`fill` under the mask, the two members' roundings reproduced, so the row the kernel folds
is the row the chain stored and the bits are the chain's
(`GpuTest.theFusedTierLandsOnTheComposedDeviceChainsBitsAtBothWidths` runs the scaled,
masked chain and the fused pair at both widths and both mask widths). The adjoint applies
`where(mask, 0, ·)` and then the scale in the store, the tape's order. The mask must be a
TRAILING block of the operand (its dims, leading 1s dropped, a suffix of the operand's --
the `(1 256 256)` causal mask over a `(64 256 256)` score) and may be either width; any
other mask, any other axis, declines to the defun, whose three members the device then
takes one by one as before.

**The premise that the fusion "would cost the kernel nothing" was wrong, and the first
build measured as a wash.** Kernel time per call at the book's score shape, f32, chain
against fused, isolated (`probe2` in the session's scratch; the chain's softmax over a
masked row is faster than over a plain one because `exp(-inf)` is):

| | chain | fused, mask read as it is | fused, mask PACKED |
|---|---|---|---|
| forward, `/ 8` and the causal mask | 0.517 ms (softmax 0.248 + scal 0.163 + where 0.106) | 0.358 | **0.305** (incl. `pack_mask` 0.008) |
| adjoint, the same | 0.671 (0.437 + 0.122 + 0.111) | 0.652 | **0.501** |
| forward, mask alone | 0.384 | 0.359 | 0.286 |
| adjoint, mask alone | 0.557 | 0.623 | 0.491 |
| forward, `/ 3` alone (a real divide) | 0.435 | 0.397 | 0.413 |
| adjoint, `/ 3` alone | 0.625 | 0.559 | 0.569 |

The mask read inside the row kernel cost about what the `where` pass it replaced cost,
and in the adjoint MORE (0.19 against 0.11): the row kernels run one thread per row --
16384 threads at this shape, a tenth of the card -- so a load per cell is exposed latency
there, where the element-wise `where` hid the same loads under four million threads.
(Neither the fp64 compare, nor a 64-bit modulo per cell -- that one DID double the kernel
on its own and is gone -- nor the loop's unrolling was the difference; each was measured.)
What fixed it: the mask reaches the row kernel PACKED, one bit a cell, through a
`pack_mask_*` launch the same call makes just before (8 µs), and with the mask a whole
number of 32-aligned rows a lane loads ONE word for its row and the thirty-two lanes
exchange bits by shuffle -- thirty-two loads a chunk become one. Two more things in the
kernel comment: a `__shared__` tile per template instantiation cost the PLAIN softmax 30%
(the tiles are declared once, in the dispatcher), and the forward's first pass writes the
scaled, masked row into the result as scratch so the exp pass reads it back rather than
paying the mask and the divide twice. The plain pair (no scale, no mask) is the pre-641
body verbatim. A real divide (`/ 3`) is still `div.rn.f64` per cell, once; the book's
`sqrt 64` is the multiply.

**Measured, batch 64** (`gpt-book-shapes-fast.lisp`, `--gpu --simd`, JVM class output,
GB10 with the machine to itself; kernel columns nsys over 13- and 3-step runs, the
difference over 10 steps; the step `(t23 - t3) / 20`, three interleaved rounds):

| | before | after |
|---|---|---|
| `scal_f32` at grid 16384 (the score) | 72 launches, 7.92 ms | **0** |
| `where_f32` at grid 16384 | 72, 7.82 ms | **0** |
| `softmax_f32` at grid 256 | 36, 9.18 ms (0.255 a call) | 36, 11.78 ms (0.327) |
| `softmax_grad_f32` at grid 256 | 36, 15.98 ms (0.444) | 36, 16.67 ms (0.463) |
| `pack_mask_f64` | -- | 72, 0.59 ms |
| total kernel time a step | 475.1 ms in 2492.5 launches | **464.2 ms in 2413.8** |
| wall a step, the three rounds | 0.606 / 0.577 / 0.616 | 0.586 / 0.627 / 0.592 |
| wall a step, median | 0.606 s | **0.592 s** |

11 ms of kernel time a step (2.3%), and 144 fewer launches with their 72 fresh 16.8 MB
results (1.2 GB of allocation a step). On Metal the scaled-masked forms DECLINE (the plain
pair is fused there since todo-636), so the composition runs member by member as before;
whether the fold pays on that backend is for a measurement on a Mac. The wall's spread (±4% here, the usual) is wider
than its 2.3% median move, so the kernel column is the number. With the mask read as it
was, the same profile gave 477.2 ms -- the buckets moved, the total did not -- which is
the measurement the packing was built on. **The loss series is byte-identical to the
previous build's at every step of all six runs and both profiles.**

**On Metal the fold is built too, and is worth 15% of the step there** -- six times what
it took here, and for a reason this backend could not have guessed: the causal mask is a
`double[]`, which `whereF` refused on METAL until todo-645 ("The `where` mask's width"
below), so the chain's `masked-fill` was running on the CPU over a materialized score.
"The attention scale and mask on Metal" below has the numbers.
**CUDA is not the backend that had that hole**: `where` here takes the mask's width
independently of the value's (`mkind` is 1 for a `float[]` mask and 2 for a `double[]`
one, and the staging is sized off `mwidth`), so a double mask is a device member here and
always was -- which is why the `where_f32` row above has 72 launches to remove rather
than a CPU pass. So the two backends' numbers are not the same measurement: -2.3% here is
two device passes removed, and 15% there is two device passes plus one host pass.

What is left at the head after this: `copy_f32` at grid 4096 (72 a step, 2.9 ms) is
`torch:cat`'s slice adjoint over the six heads, and the softmax pair itself is 28 ms a
step -- at 0.33 / 0.46 ms a call, each about 1.5-2x the memory floor of its passes, the
price of the one-thread-per-row shape the sequential double folds require.

## Layer-norm's affine (todo-634, 2026-09-02)

The last of the three chains `.todo/629` measured and did not build. `torch:layer-norm`'s
module forward ended in `(torch:add (torch:mul norm weight) bias)` -- three tape nodes over
the fused normalization -- and at the book's shapes, batch 64, thirteen layer-norms a step,
that affine was four whole passes over a 25.2 MB activation per call: two broadcasts
forward, a broadcast `g * weight` and a zip `g * norm` backward. **All four are inside the
pair now** (`%la-layer-norm-affine` / `-affine-grad`, `.kb/linalg.md`; the tape side is
`.kb/torch.md`, "The fused compositions"), and the item's own question -- whether the fold
pays once the WEIGHT's gradient has to be recovered without `norm` -- is answered by the
adjoint kernel writing **two results**: `dx` and `g * norm`. The row statistics it
recomputes anyway ARE what `norm` is made of, so the second result costs its store.

**Measured first, in isolation, before any of it was wired** (a standalone `nvcc` probe
over the checked-in `gemm.cu` plus the candidate kernels, `(64 256 384)` at `#f`, 50 calls,
three rounds, the chain and the fused member launched back to back):

| | chain | fused |
|---|---|---|
| forward: `layer_norm` + two `bcast` | 0.915 ms | **0.484** |
| backward: `bcast` + `layer_norm_grad` + `zip` | 1.586 ms | **1.006** |
| (`layer_norm_grad` alone, for scale) | 1.030 ms | -- |

The adjoint pair costing what the plain adjoint alone cost is not luck: the extra
`g * norm` store is one pass, and the three separate `j` loops of the plain adjoint's last
chunk pass -- the terms, the `OLD` fold, the combine -- collapse into ONE, which pays for
it. The parameters cost the row kernel nothing: their column index is UNIFORM across the
warp, so each is a broadcast load out of a 1.5 KB vector that stays in cache for every row.
That is the difference from todo-641's mask, which was a cell per cell and had to be packed
-- **"fusing costs the kernel nothing" is still not a premise, it is a measurement each
time**.

**Measured in the step, batch 64** (`gpt-book-shapes-fast.lisp`, `--gpu --simd`, JVM class
output, GB10 with the machine to itself; the kernel columns are the difference between
nsys over 13- and 3-step runs, i.e. ten steps; the step is `(t23 - t3) / 20`, median of
three interleaved rounds). BATCH 64 numbers, like the three tables above:

| | before | after |
|---|---|---|
| `layer_norm_f32` / `layer_norm_grad_f32` at grid 256 | 13 + 13 launches, 4.63 + 16.04 ms | **0** |
| `layer_norm_affine_f32` / `..._grad_f32` at grid 256 | -- | 13 + 13, **4.73 + 18.37 ms** |
| `bcast_f32` at the activation grid | 52 launches, 10.97 ms | **13, 2.93 ms** |
| `zip_f32` at the activation grid | 153, 48.51 ms | **140, 43.83 ms** |
| total kernel time a step | 465.9 ms in 2413.8 launches | **456.8 ms in 2359.2** |
| wall a step, the three rounds | 0.572 / 0.567 / 0.565 | 0.558 / 0.559 / 0.569 |
| wall a step, median | 0.567 s | **0.559 s** |

9.0 ms of kernel time a step (1.9%), and 52 fewer launches with their 52 fresh 25.2 MB
results -- about 1.3 GB of device allocation a step. The wall moved 1.4%, inside its own
±4% spread, so **the kernel column is the number**, as it was for todo-641. The forward
fold is nearly free (+0.11 ms over thirteen calls for two passes removed); the adjoint's
second result costs 2.3 ms over thirteen and removes 12.7. **The loss series is
byte-identical to the previous build's at every step of all eight runs and both profiles.**

**What it costs elsewhere, both measured.** The four kernels add 13 k lines of PTX --
1.55 -> 1.89 MB, +22% -- and the PTX travels in every `--gpu` class, which grows the
book-shapes class 2.52 -> 2.87 MB (+14%); each new kernel is the size of its plain sibling
(2.1 k lines the forward, 4.4 k the adjoint), so this is the tier's own established price
rather than a new one, and the driver's JIT of the larger text costs nothing measurable
(the 3-step wall, which is mostly setup, went 5.37 -> 5.22 s). On the CPU the backward
recomputed `norm`, +28% on layer-norm's backward and nothing else -- fixed as `.todo/644`
by a `%la-layer-norm-grad` sibling that answers `(dx norm)` from the pass it already makes,
back to about the pre-fusion time (`.kb/linalg.md` has both measurements). Metal DECLINES both members, at both widths, so the
module runs the normalization and its two broadcasts member by member there exactly as
before -- todo-646 built the MSL pair and measured it rather than leaving it a guess, and
declined it on the step: "Layer-norm's affine on Metal: built, measured, NOT kept" below.

## The chains left composed (todo-629, 2026-09-02)

The list `.todo/499` left behind: five compositions that still ran one memory pass per
`linalg:` member, none worth a round on its own. **Measured member by member first, at
BATCH 64 and the book's shapes** (`chains-baseline.lisp` + `fusion-segments.py`, the
per-member device cost; the operands of the scalar tier are made resident by a device
member first, because that tier is offered over a resident operand only and a host array
silently measures the CPU). Two paid and are built; three did not and are recorded here
with the numbers that say so.

**Built: a division by a power of two is launched as the multiply.** The scalar tier
computes in DOUBLE and narrows on the store, which is the CPU kernel's contract -- and a
`div.rn.f64` is the one arithmetic operation this card is slow at. Isolated, a
`(64 256 256)` `#f` scale is 0.140 ms as a multiply and 0.195 as a divide; in the step,
where the fp64 pipe contends with everything else queued, the same launch is 0.272 ms.
`(torch:div score (sqrt d-k))` and its adjoint are 72 of those a step. **Dividing by a
power of two is exactly multiplying by its reciprocal** -- two correct roundings of the
same real number, for every operand including subnormals, infinities and negative zeros --
so `Gpu.scale` rewrites `op == BIN_DIV` with an exact reciprocal into `BIN_MUL`, in the
one place both backends pass through. The reciprocal must be normal at BOTH widths
(`Gpu.exactReciprocal`), because a backend that computes in `float` (Metal) would
otherwise multiply by one that underflowed to zero there. `GpuTest`'s
`aDivisionByAPowerOfTwoIsTheExactReciprocalsMultiplyAtBothWidths` asserts equality with
the CPU's own divide over every divisor, power of two or not; `GpuDeclineTest` pins the
predicate on every machine.

**Built: the last-axis `log-softmax` and its adjoint are row kernels.** `log_softmax_*`
and `log_softmax_grad_*` in `gemm.cu`, on the same transposed-tile layout as the softmax
pair, reached through `linalg:log-softmax` in its `:axis` form and the new internal
`linalg::%la-log-softmax-grad` (the four members `torch.lisp`'s adjoint always was). The
forward's three passes recompute the deviation rather than store it -- the same `(T)`
subtraction, one memory pass saved. Both are the chain rounding for rounding, so
`GpuTest.theFusedTierLandsOnTheComposedDeviceChainsBitsAtBothWidths` runs the six-member
chain and the fused kernel and asserts EQUALITY, as it does for the other seven.

**Measured, batch 64** (`gpt-book-shapes-fast.lisp`, `--gpu --simd`, JVM class output,
GB10 with the machine to itself; the step is `(t23 - t3) / 20`, median of three
interleaved rounds; the kernel columns are nsys over a 13-step run). NOTE these are BATCH
64 numbers -- the fused-tier table above is batch 32:

| | before | after |
|---|---|---|
| `scal_f32` at the score shape | 72 launches, 19.6 ms | **72, 7.8 ms** |
| `fold_f32` at 3038-wide rows (in the 16384-cell bucket) | 111 launches, 8.5 ms | **108, 2.3 ms** |
| `bcast_f32` / `map_f32` / `zip_f32` at the logits | 6 launches, 12.0 ms | **0** |
| `log_softmax_f32` + `log_softmax_grad_f32` | -- | **2 launches, 10.4 ms** |
| total kernel time a step | 503 ms in 2597 launches | **485 ms in 2589** |
| wall a step | 0.676 s | **0.608 s** |

The wall moved 68 ms where the kernels moved 19, and the six removed launches are why:
each was a fresh ~199 MB result at the logits shape, so the step also stopped churning
about 1.2 GB of device allocation. Device-busy share went 74% -> 80%. **The loss series is
byte-identical to the previous build's at every step of all six runs.**

### The three that did not pay, with the numbers

- **The attention scale and mask around each softmax** was the biggest one left --
  `scal_f32` 7.8 ms a step after the rewrite plus `where_f32` 7.8 -- and was NOT a
  fusion this tape could express: `torch:div` and `torch:masked-fill` were EAGER nodes,
  so by the time `torch:softmax` saw the masked score both passes had been paid. Built
  behind `.todo/630`'s view machinery as `.todo/641`: "The attention scale and mask"
  below, with the measurement -- the two buckets are gone, and what the fused kernels
  give back is less than the two passes cost, for a reason the section states.
- **Layer-norm's affine** (`* weight + bias`) is 15 ms a step at these shapes: 2 broadcast
  passes forward (0.213 + 0.214 ms x 13), and backward a broadcast mul (0.215), a zip mul
  (0.314) and the two axis-0 folds per parameter (0.098 each). Fusing it into
  `%la-layer-norm` recovers the forward pair and the backward's `g * weight`, about 8 ms
  -- but the weight's gradient needs `g * norm`, and once the affine is inside the node
  `norm` is no longer stored, so a separate member has to recompute the row statistics
  (two more passes over `x`) and gives 3 of the 8 back. Worth it only if the adjoint
  member emits TWO arrays, `dx` and `g * norm`, which no `linalg:` member does today.
  BUILT as `.todo/634`, and the two-output adjoint is what made it pay: "Layer-norm's
  affine" above has the numbers.
- **`gelu_grad_f32`'s 2.62 ms a call is not the two libm calls the item blamed.** The
  memory floor at that shape (three arrays, measured as a zip mul) is 1.295 ms; the
  forward, one libm call and one divide over two arrays, is 1.168 against a 0.86 floor.
  What the adjoint has that the forward does not is a SECOND `div.rn.f64` by `sqrt 2`, and
  the scale measurement above prices a double divide at 0.055 ms per 4.19 M elements =
  0.33 ms per pass at the feed-forward's 25.2 M, which accounts for most of the gap.
  Saving `t4` from the forward removes the `erf` and adds a fourth array: the floor goes
  to ~1.73 ms and the forward to ~1.75, so the step trades ~4.3 ms of backward for ~3.5 ms
  of forward and holds 600 MB more on the device for the round trip. **Declined on the
  numbers.** `sqrt 2` has no exact reciprocal, so the divide cannot be rewritten the way
  the scale's was.
- **The fused tier on Metal** was carved out as `.todo/636` and is now built -- eight of
  the nine members, the row kernels' sequential double folds running on the software
  binary64 the resident tier already needed. "The fused tier on Metal" below has the
  numbers.

**One measurement to carry forward: the last-axis fold is uncoalesced.** `fold_f32` with
`inner == 1` gives thread `i` row `i`, so thirty-two lanes read addresses a row apart --
the pattern the row kernels were built to avoid. Over 3038-wide rows that is 199 MB in
2.06 ms (**97 GB/s**); over 384-wide rows the same kernel reads 25 MB in 0.098 ms (255
GB/s), because at that row length the block's working set stays in cache. The fused
log-softmax inherits the row kernels' tiling and is why that bucket fell 8.5 -> 2.3 ms;
every other last-axis fold over a long row still pays it. Followed up as `.todo/635`,
whose answer is the next section: the premise held, the tiled kernel is 1.7-2x, and
NOTHING IN THIS REPOSITORY FOLDS A LAST AXIS ON THE DEVICE ANY MORE, so it is not built.

## The last-axis fold's tiling: measured, and declined on the census (todo-635, 2026-09-02)

The paragraph above is confirmed and closed. The tiled fold was written and measured in a
standalone `nvcc` probe -- the checked-in `gemm.cu` compiled verbatim (`-fmad=false`, the
header's own flag) plus a `fold_rows_f32` entry point beside it, timed against the plain
`fold_f32` back to back and asserting BIT EQUALITY of all 16384 results per shape. The
candidate is the item's own design: one thread per row, the rows streamed thirty-two
columns at a time through the row kernels' `row_tile` / `tile_load`, the accumulator a
sequential `double`, so the fold ORDER never moves. It is bit-equal to the plain kernel at
every shape probed (row counts 100 / 1000 / 2048 / 4096 / 6144 / 8192 / 10240 / 12288 /
16384 / 65536 x sixteen widths from 32 to 4096 x all three ops), including row counts and
widths that are not multiples of thirty-two.

**It works.** At the book's 16384 rows, `sum` at `#f`, 50 back-to-back calls, median of
three rounds -- each round preceded by 200 heavy launches, because the clocks are still
climbing through a light kernel and a sweep that starts cold measures its first widths
10-15% slow (the absolute rates carry that much run-to-run spread; the ratios do not):

| row width | plain | tiled | |
|---|---|---|---|
| 256 | 0.069 ms (242 GB/s) | 0.052 (322) | 1.33x |
| 384 | 0.112 (225) | 0.084 (300) | 1.33x |
| 512 | 0.244 (137) | 0.146 (230) | 1.68x |
| 768 | 0.444 (113) | 0.244 (206) | 1.82x |
| 1024 | 0.607 (111) | 0.319 (211) | 1.90x |
| 1536 | 0.946 (106) | 0.483 (209) | 1.96x |
| 3038 (the logits) | 1.883 (106) | 1.127 (177) | 1.67x |
| 4096 | 2.418 (111) | 1.275 (210) | 1.90x |

The plain kernel's rate falls off a cliff between 384 and 512 wide -- 225 -> 137 GB/s, and
~110 for every longer row -- which is the 25 -> 34 MB working set leaving the cache and is
what 629's "384-wide rows are fast" observation was. The tiled kernel holds ~210 GB/s
whatever the row length, as its coalesced stream should.

**What decides the win is the ROW COUNT, not the byte count** -- one thread per row is all
the parallelism either kernel has, and the tiled one pays a barrier per thirty-two-column
chunk that only enough blocks can hide. At a fixed ~50 MB operand: 16384 rows x 768 =
1.82x, 12288 x 1024 = 1.80x, 8192 x 1536 = 1.57x, 6144 x 2048 = 1.38x, 4096 x 3038 =
**0.99x**, and 2048 x 4096 (34 MB) = **0.89x**. So the threshold would have been on the
output cell count, at about six thousand rows, with the plain kernel kept below it.

**The gap sweep says the 97 GB/s is the access pattern, not the measurement context.**
The Metal backend's `.todo/642` found that a kernel launched as the first thing after a
28-30 ms host gap runs at dropped clocks, four times slower than the same kernel measured
back to back -- so a size threshold measured back to back can be measured in a context the
chain never sees ("The map threshold at the straddling shape", below). **That trap has no
CUDA twin on this card.** The same single launch, the host spinning 0 / 0.5 / 1 / 2 / 4 /
8 / 32 ms immediately before it, median of 25, three rounds:

| | gap 0 | 0.5 | 1 | 2 | 4 | 8 | 32 ms | back to back |
|---|---|---|---|---|---|---|---|---|
| plain, 16384 x 3038 | 1.869 ms | 1.868 | 1.866 | 1.866 | 1.866 | 1.871 | 1.867 | 1.896 |
| tiled, the same | 1.126 | 1.125 | 1.126 | 1.127 | 1.121 | 1.128 | 1.125 | 1.135 |
| plain, 16384 x 384 | 0.096 | 0.096 | 0.098 | 0.097 | 0.098 | 0.099 | 0.099 | 0.099 |

(this probe's own warm-up is heavier again, which is why the short row reads 0.099 here
against 0.112 in the table above -- inside the spread that table names.) Flat to within 1% out to a 32 ms idle -- the driver holds the clocks (persistence mode is
on) -- and the 3038-wide row measures 107 GB/s against the 384-wide row's 256 in EVERY one
of those contexts. **So a back-to-back probe is a valid context for a CUDA threshold**,
which is not something to assume on the other backend.

**It is not built, because nothing reaches it.** `CudaGemm.fold` was instrumented to print
every shape it is asked for, and the book's step at BATCH 64 (`gpt-book-shapes-fast.lisp`,
`--gpu --simd`, JVM class output) makes **864 fold calls over two steps and not one of
them has `inner == 1`**:

| calls (2 steps) | shape | |
|---|---|---|
| 216 | `outer=1 len=64 inner=24576` | |
| 216 | `outer=1 len=64 inner=16384` | |
| 216 | `outer=1 len=256 inner=64` | this is 629's "16384-cell bucket" |
| 78 | `outer=1 len=64 inner=98304` | |
| 76 | `outer=1 len=256 inner=384` | |
| 24 + 12 + 12 + 12 + 2 | `inner` = 589824 / 393216 / 147456 / 1536 / 1166592 | |

Every one is an AXIS-0 fold -- over the batch (`len=64`) or the sequence (`len=256`) --
whose thirty-two lanes are contiguous cells of the same row and therefore already
coalesced. `fold_f32` is 25.6 ms of the step's 456.8 (5.6%, its nine grid buckets summed),
and the tiled kernel would take none of it. The bucket 629 named is `len=256 inner=64`,
not a 3038-wide row: the 3038-wide last-axis folds 629 measured are exactly the ones 629
REMOVED, by fusing `log-softmax` -- and the fused tier has since taken the last-axis
softmax, log-softmax, layer-norm and their adjoints, which were where a long last-axis
fold came from. Outside the step, six of the repository's `linalg`-heavy programs run
under `--gpu --simd` (`llm-from-scratch` chapter02 sections 2-5, `transformer/shapes`,
`gpt/shapes`) make **zero device fold calls of any kind** -- their shapes decline to the
CPU at the fold threshold.

**Build it when a workload folds a LAST axis of more than ~256 elements over more than
~6000 rows on the device** -- a hand-written reduction outside the eight fused
compositions, which is what a new model's own code looks like. The kernel is an hour: a
`fold_rows_f32` / `_f64` pair beside `fold_f32` (a SEPARATE entry point, not a template
instantiation of the plain one -- an instantiation that carries a `__shared__` tile costs
the plain path 30%, `.todo/641`), the body above, `CudaGemm.fold` choosing on
`inner == 1 && cells >= threshold` and launching at `ROW_BLOCK` exactly. Nothing about
`GpuDevice` changes, so Metal is untouched either way.

## The Metal backend

The same feature with a different member set. The flag, the CLI, the interception layer,
the decline protocol and the tests are shared; what is NOT shared is the width, every
threshold, and two whole tiers.

| | CUDA | Metal |
|---|---|---|
| widths | `#d` and `#f` | **`#f` only** -- MSL rejects `double` outright |
| rank-2 product | our tiled kernel | **MPS** above `2^27` per matrix, our tiled kernel below |
| stacked product | our batched kernel | our batched kernel |
| transposed stacked product | `ta` / `tb` on the same kernel | the same two flags, and MPS's own `transposeLeft:` / `transposeRight:` above the MPS threshold |
| fused tier | nine members | **eight of the nine** -- the dropout mask stays declined, on the draw's arithmetic (todo-636) |
| element-wise tier | twelve members | the same twelve |
| broadcast + axes transpose | yes | yes |
| axis fold `:axis` | yes | **not as a round trip, measured**; over a resident operand only |
| generator fill | yes | no -- it needs a `double` |
| `vec:matvec` | from `2^17`, double accumulator | from `2^21`, **compensated float** accumulator |
| lazy results + resident tier | on (`lazyResultsPay`) | **on since todo-495**, when the command buffers went asynchronous under the mode; measured off from todo-494 to then |
| resident set | every operand and result | eagerly **the GEMV's matrix only**; lazily every operand and result, under a budget that leaves the heap its memory |
| index tier + clip norm | yes | the copies over a resident operand; `sumSquares` still declined (unmeasured lazily) |
| per-call floor | 16-18 us | **77 us**, per COMMAND BUFFER, eagerly; **15-26 us a member** in a lazy chain, where nothing waits (todo-495) |
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
the gap with later. (The rule is about an operand that ENTERS ARITHMETIC. The one operand
in the library that does not is `where`'s mask, and it is taken at both widths -- "The
`where` mask's width" below.) Two consequences: **the decline protocol is load-bearing in
a way it is not on CUDA** -- `linalg`'s default width is double, so on Apple the flag is inert
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

### The transposed product on Metal (todo-631, 2026-09-02)

`gemm_batched_f32` took `ta` / `tb` the way `gemm<T>` did, in the same staging: an operand
so marked has its `M x K` (or `K x N`) matrix STORED `K x M` (or `N x K`), the load walks
it down its columns with the two thread indices swapped so a SIMD group's global reads
stay contiguous, and the tiles gained the column of padding that makes the transposed
store bank-conflict-free. The TILE the fold reads is the same tile, so the product is
bit-identical to the plain product of the transposed copy and which orientation ran is not
observable -- `MetalGpuTest.aTransposedOperandIsReadInPlaceAndFoldsOntoTheUntransposedProduct`
asserts EQUALITY, at every shape and through BOTH routes.

**MPS carries the orientation too, and that is what makes the threshold invisible.** Above
`MPS_MIN_WORK` the tiled kernel is unreachable, so a transposed product there would have
had to fall back to the copy the change exists to remove. `MPSMatrixMultiplication` takes
`transposeLeft:` / `transposeRight:` and the descriptor is then the operand's STORED shape
(`m x n` rather than `n x m`, `rowBytes` still the storage's own contiguous row) --
measured, that route lands on the tiled kernel's bits exactly as the plain pair does, which
is what the test asserts at the 1000x1000x1000 shape by running it both ways.

**Measured, batch 64** (`gpt-book-shapes-fast.lisp`, `--gpu --simd`, JVM class output,
M4 Max 40-core / 128 GB with the machine to itself; the step is `(t13 - t3) / 10`, three
interleaved rounds, the same program the CUDA tables use):

| | before | after |
|---|---|---|
| wall a step, the three rounds | 11.738 / 8.760 / 8.440 | **8.014 / 7.980 / 8.325** |
| wall a step, median | 8.760 s | **8.014 s** |

Every AFTER round beats every BEFORE round, which is the shape the CUDA measurement warned
to look for rather than a single pair: the before rounds span 39% (the first is a cold
one), the after rounds 4%. **The loss series is byte-identical to the previous build's at
every step of all six runs**, as the bit-identity above says it must be.

What did NOT transfer from the CUDA half: the register-tiled kernels, which this backend
does not have -- above the MPS threshold MPS is the fast path and below it the 16x16
kernel is the only one -- so there is no transposed-tile-versus-untransposed cost to weigh
here, and no `_t4` / `_t8` regression to trade against the removed pass.

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

### The fused tier on Metal (todo-636, 2026-09-02)

Eight of `gemm.cu`'s nine fused members, in MSL: the exact GELU and its adjoint, the
last-axis softmax and its adjoint, the last-axis log-softmax and its adjoint, layer-norm's
normalization and its adjoint. The row kernels are `gemm.cu`'s -- one THREAD per row,
thirty-two rows streamed through a transposed threadgroup tile thirty-two columns at a
time, two SIMD groups to a threadgroup -- and what makes them possible here is the
software binary64 the resident tier already needed: the sequential `double` fold has no
float form that keeps its bits, and `f64_add` over a widened float is the CPU's own
accumulation.

**Every member boundary goes one of two ways, and which one is not a choice.** `gemm.cu`
spells a boundary `(T)((double) a op (double) b)`; with `T = float` and no `double`, a
boundary whose operands are both floats IS that rounding taken once (`bin_op_exact`, the
flush guard included), and a boundary against a constant the float grid does not hold --
`1/sqrt 2`, `2/sqrt pi`, the layer-norm `eps` -- takes the software route, which is
exactly what the chain's own `scal_f32` takes for those scalars.
`MetalGpuTest.theFusedTierLandsOnTheComposedDeviceChainsBits` runs each chain member by
member over RESIDENT operands (on this backend a per-row intermediate is below every size
threshold, so residency is the only way to get an all-device chain) and asserts EQUALITY
against the fused kernel. It passed on the first run for all eight.

**Per call, best of five, M4 Max at the book's own shapes** (`--gpu --simd`, `java -jar`,
ms):

| composition, shape | chain | fused | |
|---|---|---|---|
| `softmax :axis -1`, `(24576 256)` | 12 | **2** | 6.0x |
| its adjoint | 7 | **5** | 1.4x |
| `log-softmax :axis -1`, `(16384 3038)` | 83 | **21** | 4.0x |
| its adjoint | 66 | **24** | 2.8x |
| `%la-gelu`, `(16384 1536)` | 36 | **8** | 4.5x |
| its adjoint | 84 | **17** | 4.9x |
| `%la-layer-norm`, `(16384 384)` | 13 | **3** | 4.3x |
| its adjoint | 47 | **8** | 5.9x |

**The step, batch 64** (`gpt-book-shapes-fast.lisp`, `--gpu --simd`, JVM class output, M4
Max with the machine to itself; `(t13 - t3) / 10`, three interleaved rounds, against the
build that already had the transposed product):

| | before | after |
|---|---|---|
| wall a step, the three rounds | 9.940 / 8.663 / 7.567 | **5.552 / 7.440 / 5.901** |
| wall a step, median | 8.663 s | **5.901 s** |

Every after round beats every before round. A THIRD off the step, where the CUDA fused
tier took a quarter -- larger here because this backend removes four command buffers out
of five as well as the memory passes: a Metal call is `commit` plus `waitUntilCompleted`
and nothing overlaps, so a chain of five members is five full waits.

**A FUSED MEMBER CAN MOVE BITS THE CHAIN DID NOT, wherever the chain STRADDLED a
threshold.** The general rule, because it will come up again the next time a fused kernel
is added here: a chain's member that falls UNDER a size threshold runs on the CPU, in
Java's libm; a fused kernel runs every member of that chain on the device, in the shader's.
Where the chain was entirely on one side, fusion changes nothing; where it straddled, the
fused member carries a device `log` (or `exp`, or `erf`) the chain took from the host. This
is not a new property of the tier -- it is the corollary of two that are already recorded
above: "The transcendentals have their own libm, and that break can be SEEN", and the
threshold-dependent output the same section pins for `(linalg:erf #d(-0.0))`, which prints
`-0.0` above the threshold and `0.0` below it. The straddle was always there; fusion makes
it visible in one call instead of two.

**Measured, this tier: it costs the log-softmax pair and nothing else.** Member by member
at `(16384 64)`, six of the eight are byte-identical to the unfused build -- softmax, its
adjoint, the GELU, its adjoint, layer-norm and its adjoint. `log-softmax` and its adjoint
are not, and the straddling member is exactly one: the chain's
`(linalg:log (linalg:sum ... :keepdims t))` is a `log` over a `rows x 1` array, 16384
elements at the book's shapes, under this backend's map threshold of 2^17. On CUDA the
same array CLEARS that backend's lower map threshold -- the chain there is all-device, and
that is why todo-629 measured the loss byte-identical there and this does not. Declining
the pair would restore byte-identity and cost 104 of the ~330 ms the table above gives
back, which is why it is not declined; a program that needs the previous bits on Apple
turns the flag off rather than this tier. **What would remove the straddle rather than
pay it is a lower map threshold for the unary libm members here -- and the measurement
says no**, for a reason the threshold's own numbers could not have shown: "The map
threshold at the straddling shape" below.

**The dropout mask is the ninth, and it stays declined -- on the ARITHMETIC.**
Wichmann-Hill's uniform is three binary64 divisions and two additions an element, and a
binary64 division here is the software restoring divide, fifty-five bit-serial steps. That
is why `rngFillF` is not a member on this backend either, and fusing the comparison and
the scale onto the draw does not change which half is expensive: it is the draw, not the
two passes over it. `MetalGpuTest.theDropoutMaskStaysDeclinedHere` pins both.

**The offer rule needed a threshold of its own.** `Gpu.offeredRows` took the FOLD
threshold for the libm-free members, and this backend's fold threshold is `Long.MAX_VALUE`
-- the measured refusal of the round-trip axis fold -- so layer-norm and its adjoint could
never have been offered. A fused row kernel does not replace one fold; it replaces a chain
of memory passes and command buffers, so the fold's answer is the wrong one to reuse.
`GpuDevice.Thresholds` gained a `fused` field: CUDA passes its own fold threshold (nothing
moves there) and Metal passes `MIN_MAP_ELEMENTS`.

#### The libm-free members against a SEQUENTIAL replay, not against the chain (todo-665, 2026-09-03)

The tier's own test above holds a fused kernel to the CHAIN OF DEVICE MEMBERS it replaces,
rounding for rounding, both sides on this device. `GpuTest` makes a strictly stronger claim
for the members with no library function in them: they equal a SEQUENTIAL JAVA REPLAY of
that chain, so the device's answer is the CPU's rather than merely the device chain's.
todo-662 left this as the one gap of eighteen it did not close, because it is a MEASUREMENT
whose answer was not known.

**It holds, on the first run, for every applicable member.**
`MetalGpuTest.theLibmFreeFusedMembersAreTheSequentialReferencesBits` pins layer-norm, its
adjoint onto a fresh gradient, its adjoint onto an accumulated one, and the softmax adjoint
-- bit for bit against `GpuTest.layerNormGradReference`'s walk ported to `#f`, the only
width here. No member diverged, so nothing had to be weakened to a bound.

**Which members are in scope.** Of the tier's eight here, five carry a libm call by
construction -- the softmax and log-softmax forwards and the log-softmax adjoint take
`exp`, the GELU pair takes `erf` and `exp` -- and the shader's libm is not Java's (see
"the transcendentals have their own libm" above), so a sequential replay could only ever be
a BOUND for those. What is left is arithmetic, comparison and ONE square root, which is
correctly rounded on both sides (`precise::sqrt` here, `Math.sqrt` there). The three other
libm-free members `GpuTest` covers are NOT MEMBERS on this backend and there is nothing to
replay: the dropout mask (`theDropoutMaskStaysDeclinedHere`) and layer-norm's affine pair
(`theIndexTierTheClipNormAndTheAffinePairAreNotMembersHereAndDeclineOverAResidentOperand`).

**Why it can hold at all -- and why the reason the item gave for doubting it was wrong.**
The item was raised on the premise that the fused kernel does its row reduction as a
THREADGROUP TREE, and a tree and a sequential sum agree only when every partial is exact.
That premise is false about these kernels. `gemm.metal`'s row members run ONE THREAD PER
ROW and fold that row SEQUENTIALLY -- `f64_add` over the widened float, in index order,
which is `%la-fold-axis`'s own accumulation -- in the software binary64 the resident tier
already needed. The `row_tile` is a TRANSPOSED LOAD for coalescing (thirty-two rows through
one SIMD group, thirty-two columns at a time), not a reduction. There is no reassociation
anywhere in the fold, so there is nothing for a bound to forgive. **The premise cost
nothing because the kernel was read before the machine time was spent**, which is
`.kb/measurement-probes.md` rule 4 run forwards rather than recovered from.

**One thread per row is the same fact todo-641 and todo-643 met as a COST.** There it is
why a mask read a cell at a time exposed its latency with nothing to hide behind -- 16384
threads is not enough of them -- and why the mask had to be packed a bit a cell and traded
through a shuffle ("The attention scale and mask" and its Metal half). Here it is why a
sequential replay is the right oracle at all. Neither reading is the property: a shape with
too little parallelism to hide a load is also a shape with no reassociation to forgive, and
which of the two you meet depends on the question. Read either section alone and the shape
looks like a weakness or like a guarantee; it is both.

**What the test is sized off, and the mutation that shows it pins something.** Every shape
comes from the thresholds in force -- `Gpu.fusedMinElements()` in elements and
`Gpu.foldMinCells()` in rows, 342 x 384 today -- and nothing is made resident, so the SIZE
is what carries the accept and a moved floor is caught rather than silently declined. With
`rows` forced to 64 the members decline and the four `isTrue()` assertions fail, so the
test is not one of the vacuous ones the sweep above found. The residency census
(`residencyHits() + residencyMisses()`, eight lookups over the four calls) is the second
observable, per `.kb/test-execution.md`.

### The map threshold at the straddling shape (todo-642, 2026-09-02)

The 2^17 above was set against `sin` over a WHOLE array; what straddles it is a chain's
per-row intermediate, a `rows x 1` array. `.todo/123-gpu-acceleration/MtlPerRowMap.java`
takes it at that shape: `log` over a freshly written f32 operand, per call in us, the
operand rewritten before every call and every call timed on its own, best of five rounds
of two hundred, three runs, M4 Max.

| elements | CPU `(float) Math.log` | device, back to back | device, behind the chain's gap |
|---|---|---|---|
| 4096 | 14-16 | 98-144 | 428-509 |
| 8192 | 31-32 | 102-137 | 422-515 |
| **16384** (the book's) | **62-66** | 98-137 | 419-510 |
| 32768 | 126-142 | 111-125 | 428-522 |
| 65536 | 248-261 | 106-140 | 270-537 |
| 131072 | 495-564 | 139-163 | 391-574 |
| 262144 | 992-1114 | 169-192 | 506-622 |

**The third column is the one the chain gets, and it is the whole answer.** This backend
refuses the axis fold at EVERY size (the fold threshold is `Long.MAX_VALUE`), so the `sum`
that writes this operand is a CPU loop -- 28-30 ms of it at the book's `(16384 3038)` --
and the GPU has been idle for all of it. The same call behind a growing gap: 111-139 us at
0 ms, 147-154 at 1 ms, then 401-419 at 2 ms, 424-467 at 4, flat to 478-653 by 32 -- the
~0.5 ms clock ramp "Residency and the GEMV on this backend" measures, paid by a call that
is 100 us of work. So the two crossovers are different numbers: back to back the device
passes the CPU near **2^15**, and behind the gap at **2^17..2^18**, which is where the
threshold already is. At the book's 16384 the CPU wins by 1.5-2.2x back to back and by
6-8x in the chain.

**The straddle stays, and it is the price of a threshold that is right rather than the
symptom of one that is wrong.** (The gap is the EAGER chain's; lazily -- the interceptors'
mode since todo-495 -- the `sum` runs over a resident operand on the device and the
per-row `log` is offered for its residency, so the chain would not straddle at all. It is
unmeasured, because the fused pair replaces the chain either way.) The finding generalizes past this member: a size threshold
measured back to back is measured in the wrong context for any member whose operand a
REFUSED member produced, because the refusal is also the idle that costs the next call its
clocks. Lowering the map threshold to catch the per-row shape would have moved a 62 us
member to 500, in exchange for last-ulp agreement with a fused tier that replaces the
chain rather than running beside it.

**This is a Metal finding, not a device finding.** todo-635 ran the same gap sweep on
CUDA -- the host spun 0 / 0.5 / 1 / 2 / 4 / 8 / 32 ms before one launch, median of
twenty-five, three rounds, persistence mode on -- and the column is flat to within 1%:
a plain 16384x3038 fold is 1.869 ms at 0 ms of gap, 1.866 at 2, 1.867 at 32, against
1.896 back to back. There is no clock ramp to pay there, so back to back IS the right
context for a threshold on that backend, and the rule above must not be carried across.
The generalization that survives both is narrower: **a threshold is measured in the
wrong context whenever the backend's clocks depend on how long it has been idle** --
which is a measurement per backend, not a property of devices.

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

**Superseded 2026-09-02 by "Asynchronous command buffers on Metal" below: the interceptors
DO switch it on now.** What follows is the synchronous measurement, kept because its three
reasons are what todo-495 answered one by one -- the first by not waiting, the second by a
budget that counts the heap, the third by finding it was never the cost.

Built here, bit-identical, pinned -- and, until todo-495, **the interceptors did not switch
it on, because it did not pay.** The mode works: a member's result slab becomes the host array's DIRTY entry
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
**The first item above was the lever** -- committing without waiting and waiting only at
the first host touch overlaps the host with the device the way CUDA does, and it changes
when a slab may be recycled, the one ordering the residency design exists to forbid. That
was `.todo/495`, below; the second item was `.todo/492`'s stubs, built in the library and
unmeasured here until then.

**The Java boundary, measured here too, finds nothing to defeat.** The same harness
(`examples/jvm/bench/`, `./run.sh gpu`, 200 chained GEMVs over a resident 2048x2048 f32
matrix, M4 Max 40-core GPU + GraalVM 25.0.3) answers all three of CUDA's questions with the
eager tier above. The Java chain through the `RontoFloatArray` handle runs at **0.128-0.140
ms/iteration against the same chain inside Lisp's 0.127-0.142 and a per-call materializing
chain's 0.127-0.149** -- one number, three ways -- and all three upload the vector **200
times** (1600 KB), the Lisp-internal chain included, because a result that came home
eagerly has to go back up whatever the boundary does. So the handle's non-materializing
wrap costs nothing here and buys nothing either: what it protects on CUDA is exactly the
traffic this backend has already decided to pay, and on unified memory that upload is a
memcpy into a shared slab. `toArray()` then moves NOTHING -- dirty 0 and backings 0,
unchanged across the read, the host array already carrying all 2048 elements rather than
the 2-element header -- and still answers the same library built without `--gpu` bit for
bit. The half that does bite is `set` INTO THE RESIDENT MATRIX, the one thing this backend
keeps on the device: the write invalidates its device copy through the same `_gpuWritten`
guard the emitted code uses, and the next GEMV sees the new weight. This is a performance
finding and not a correctness one ([jvm-export.md](jvm-export.md)); it becomes load-bearing
the day `.todo/495` makes lazy results pay here, which is why `floatArrayResult` is not
being changed to materialize.

**The floor every resident-offered member is held to is `MIN_RESIDENT_ELEMENTS` = 2^14**,
and the training step put it LOWER than the crossover table says. A launch over resident
operands through the shipped route is ~100-140 us whatever its size until 2^18 and crosses
the CPU's memcpy-plus-lane-loop between 2^18 and 2^19 -- yet the step measured fastest at
2^14 (5.23-5.30 s over 40 steps against 5.72-5.92 at 2^18 and 5.79-5.83 with no floor),
because a declined member over a resident operand costs a materialize, the CPU loop and
the re-upload of its result around it, and a chain that flips between the two pays both
memcpys at every flip.

### Asynchronous command buffers on Metal (todo-495, 2026-09-02)

The first of the three reasons above is gone, and with it the decision: **the interceptors
switch lazy results on here** (`MetalGemm.lazyResultsPay()` is `true`), because under the
mode a call no longer waits. The step at the book's shapes goes **4.80 -> 1.81 s (2.65x)**,
the notebook's width **0.083 -> 0.041**, and the loss series prints the same four
decimals at every step of every run.

**What was measured first, and what it said would happen.** The waits were counted before
anything was built (a `System.nanoTime` around `commitAndWait`, per step, 13-step runs at
the book's shapes): eagerly 888 command buffers a step and 1.37-1.42 s of waiting in a
4.64 s step -- and 409-454 gaps of more than a millisecond between one buffer's completion
and the next commit, 3.1 s of them, which is the device idle and dropping its clocks
("Residency and the GEMV on this backend"). Lazily -- synchronous still, but with stubs,
which todo-494's round did not have -- the step was 5.83 s (7.66 / 5.83 / 5.82), of which
3.6-5.2 s was waiting on 1774 buffers, so the host's own share was ~2 s: overlapped
perfectly, the mode could reach max(2, 3.8) = 3.8 s and beat the eager 4.6-4.8. It
reached 1.81, because the second thing the waits cost was the clocks: a queue that never drains
never idles, and the same 1774 buffers that took 3.8 s one at a time take ~1 s of waiting
in all when the host only waits where it must.

**The mechanism.** `MetalGemm.commit` is the end of every member's encoding. Eagerly it is
`commitAndWait` as before -- the library's contract, "`out` is filled when the call
returns", cannot be met any other way, and a failed buffer is still an ordinary decline.
Lazily it commits, RETAINS the command buffer past the call's autorelease pool, gives it
a sequence number, and returns. Every slab the call held carries that number as its
`fence`; the buffers in flight sit in one deque, oldest first. One queue executes in
order, so "every buffer numbered at or below `retired` has completed" is a scalar, and
`settle(slab)` -- wait for the slab's fence, retiring everything up to it -- is the only
wait there is. It is taken at exactly the host touches:

- `stage`'s upload into a slab from the free list (a slab a dropped operand left, which a
  launch in flight may still read -- the ordering the residency design exists to forbid,
  and the one this item's ordering pin makes fail without the fence);
- every download: `materialize`, the drain's flushes, `lazyResults(false)`;
- and nowhere else. A slab taken as a RESULT needs no wait, because the device orders its
  own reuse; `enter()` polls the head of the deque without blocking so a completed buffer
  goes back to the queue promptly and a failure is learned as early as it can be.

**Failure surfaces at the first host read, never as zeros.** A buffer that ends in any
status but `Completed` is learned of only after its call answered `true`; `retire` marks
the slabs it WROTE lost, and the results of every later buffer in flight that READ one of
them -- a chain over a lost result is lost with it -- while a slab the failed buffer only
read is intact, and a slab taken fresh from the pool is clean again. A lost result throws
the `IllegalStateException` the mode already reserves for a result the host has no other
copy of, at `materialize`; a flush of one (an eviction, the switch-off) records its
storage and throws at the read instead, so switching the mode off never throws. Metal
gives a kernel no way to fail on purpose, so the pin
(`aFailedCommandBufferSurfacesAtTheFirstHostReadOfWhatItWrote`) injects the STATUS
through a package-private seam and asserts the handling.

**The second reason -- memory -- was the whole of the remaining loss, and it was the
budget rule, not the machine.** The first asynchronous build ran the first seven steps at
1.8-1.9 s and then slowed to 3.3-8 s a step with the system's time in page compression
(sys 104 s of a 146 s, 40-step run): the lazy pool budget was the working set less an
eighth, 96 GB on this 128 GB machine, beside a 24 GB heap. Three findings, each with the
numbers, decided the rule that replaced it:

1. **The pool must be sized WITHOUT the heap** -- on unified memory the slabs and the
   heap are one physical memory, and `-Xmx48g` made the same run WORSE (sys 158 s). The
   lazy pool budget is now the working set less `Runtime.maxMemory()` less an eighth (72
   GB here), which is also the sentence the guide now prints: on a Mac `-Xmx` sizes the
   pool as well as the heap.
2. **The resident budget must be counted in the pool's units.** The cache counts the
   SPANS it mirrors, the pool the power-of-two CAPACITY of its slabs, up to twice the
   span; at seven eighths of the pool the LRU never fired before the pool filled (a
   13-step run at a 72 GB pool: resident 56 GB at most, the LRU idle, `System.gc()`
   asked ZERO times), and what ran instead was the pool's own pressure path -- which
   evicted EVERYTHING, dirty copies as flushes: 1200-1500 downloads and 10-12 GB of fresh
   backings in one call, a 3.5 s step where the others were 1.8, and when the step's phase
   made it forty gigabytes, `OutOfMemoryError`. The resident budget is now HALF the pool's
   (`LAZY_RESIDENT_DIVISOR`); at that the LRU fires, the collector is asked (7 times in
   13 steps, 49 ms in all), the resident set peaks at the budget, and no flush happens
   at all. Five eighths measured the same speed with three collections; half is kept for
   the headroom.
3. **The pool's pressure path evicts a slab's worth at a time now** (`DeviceResidency.
   evictSome`: least recently used first, clean before dirty), not the whole cache -- the
   CUDA rule "the resident set must never be the reason a call declines" stands, and its
   corollary here is that neither may the whole of it be flushed into the heap at once.
   And `drop` allocates a dirty copy's backing BEFORE the entry is let go of, putting the
   entry back if the heap runs out: an `OutOfMemoryError` inside a member is caught as a
   decline, and a stub whose entry was gone and whose backing was never allocated read
   its header as its elements (`ArrayIndexOutOfBoundsException: Index 4 out of bounds for
   length 4`, the shape of every crash the sweep produced).

**The step, batch 64** (`gpt-book-shapes-fast.lisp`, `--gpu --simd`, JVM class output, M4
Max 40-core / 128 GB with the machine to itself, `-Xmx24g`; `(t13 - t3) / 10`, three
interleaved rounds):

| | eager (before) | lazy, asynchronous |
|---|---|---|
| wall a step, the three rounds | 4.77 / 4.80 / 4.91 | **1.81 / 1.81 / 1.83** |
| wall a step, median | 4.80 s | **1.81 s** |
| command buffers a step | 888 | 1774 |
| the host's waiting, a step | 1.4 s | 0.43 s |
| max RSS, 13 steps | 27.6 GB | 25.8 GB |

Steady over 40 steps (1.8-2.1 s a step from the first to the fortieth, 78 s in all against the eager build's 184, max RSS 33.6 GB), which the first build was not. Every after round
beats every before round by more than half.

**The notebook's width** (`train-gpt-soseki.lisp` at block 256, `n-embd` 384, 2 heads,
batch 4, `(t250 - t50) / 200`, three interleaved rounds), at ONE layer -- the width the
earlier rows were taken at -- and at the notebook's six:

| | eager | lazy, synchronous (todo-494's build, with stubs) | lazy, asynchronous |
|---|---|---|---|
| one layer, a step | 0.084 / 0.083 / 0.083 s | 0.078 s | **0.041 / 0.041 / 0.041 s** |
| six layers, a step | 0.443 / 0.447 / 0.439 s | 0.434 s | **0.242 / 0.242 / 0.244 s** |

**The floor** (`MtlResidentFloor.java`, a chain of resident `zip`s, per member): 15-26 us
at 2^15..2^19 where the same chain waited 100-140 us a member, 69 us at 2^20 and 99 at
2^21 -- where the device's own memory pass bounds the throughput and the queue's own
limit of buffers in flight is what the host blocks on -- against the CPU's memcpy-and-loop
at 335 and 674 us. `MIN_RESIDENT_ELEMENTS` stays at 2^14: at 16384 the first call of a
chain is 59 us against the CPU's 31, and the reason it was set there -- a declined member
in a chain costs a materialize and a re-upload around it -- is worth more under the mode,
not less.

**What did not change.** The eager path (`commitAndWait`, the per-call decline, the pool
settling) is byte-for-byte what it was, and every eager pin holds. The kernels are
untouched, so the resident tier's bits are: the loss series of the 13- and 40-step runs
print the same four decimals as the eager runs at every step. `sumSquaresF` still
declines, and the collector matrix below was re-taken because the request it gates
fires here now.

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

### The attention scale and mask on Metal (todo-643, 2026-09-02)

todo-641 folded `torch:div` and `torch:masked-fill` into the softmax pair on CUDA and left
this backend declining, with a prediction attached: worth MORE than the 2.3% it took there,
because a Metal call is `commit` plus `waitUntilCompleted` and removing two members removes
two full waits. Measured, it is worth far more than that -- **a step at the book's shapes
goes 5.50 -> 4.66 s, 15%** -- and the prediction was right for the wrong reason. The waits
are not where it came from.

**`torch:masked-fill`'s mask was never a device member here at all.** `torch:subsequent-mask`
is `(linalg:triu (linalg:ones ...))` and `linalg:ones` builds DOUBLE by default, so the
causal mask a model hands `torch:masked-fill` is a `double[]` -- and `whereF` on this
backend declines every double operand ("The resident tier", the `whereF` comment). So at
the book's `(64 256 256)` score the chain's fill ran on the CPU over a MATERIALIZED score:
16.8 MB down, a scalar select, 16.8 MB back up for the softmax to read. It measured **7.9
ms a call**, where the whole fused forward is 1.6.

The packing kernel is what lets the fold take a mask `where_f32` refuses: it reads the
operand as raw WORDS -- one per cell at f32, two at f64, the low one first -- so
`linalg:where`'s "non-zero" (`(/= m 0)`: a NaN counts, a negative zero does not) is an
integer test and neither width needs arithmetic this backend does not have.

**The shape of the build, and the two traps todo-641 flagged.**

- The scaled/masked pair is TWO NEW ENTRY POINTS (`softmax_sm_f32`, `softmax_grad_sm_f32`)
  rather than CUDA's one-kernel-with-a-dispatcher. The trap there was a `__shared__` tile
  per template instantiation costing the PLAIN softmax 30%; a second MSL entry point has
  its own threadgroup allocation by construction, so the plain kernels cannot lose
  occupancy to the new ones -- and their source is byte-for-byte the pre-643 source, so
  their bits cannot move either. Measured before and after all the same: plain softmax
  1.47 -> 1.37 ms a call, its adjoint 2.92 -> 2.92.
- The mask reaches the row kernels PACKED, one bit a cell, for todo-641's other reason: the
  row kernels run one thread per row, 16384 of them at this shape, so a load per cell is
  exposed latency there. `pack_mask` and the row kernel ride ONE command buffer as two
  dispatches of one compute encoder, which dispatches serially -- so the packing costs a
  launch and not a second wait, and on this backend a wait is what a call costs. With the
  mask a whole number of rows of 32-aligned length (the causal mask, and every last-axis
  score) a lane loads ONE word for its row and the thirty-two lanes exchange bits by
  `simd_shuffle`; otherwise each lane looks its thirty-two cells up one by one.
- The forward's first pass writes the scaled, masked row into the result as scratch and the
  exp pass reads it back, so the scale and the mask are applied once -- gemm.cu's shape,
  and the reason the fused forward is one pass more than the plain one (1.62 against 1.37,
  which is that pass).

**The scale's boundary is `scal_f32`'s, both ways**: the host decides `exact` exactly as it
decides `scal_f32`'s, so a scalar the float grid holds takes `bin_op_exact` and any other
the software binary64 route. The book's `/ sqrt 64` arrives already rewritten to the
multiply by its exact reciprocal (`Gpu.scale`'s rule, decided on the host).
`MetalGpuTest.theScaledAndMaskedSoftmaxLandsOnTheComposedDeviceChainsBits` runs three
scales -- a power of two, an exact divide, and `sqrt 2`, which is not a float -- at both
mask widths, both fills and both of the packed mask's reads, against the chain run on the
device with the fill selected on the host, and asserts EQUALITY. It passed on the first
run.

**Per call at the book's `(64 256 256)` score, f32**, `/ sqrt 64` and the causal mask
(`.todo/123-gpu-acceleration/mtl-attention-softmax.lisp`, `--gpu --simd`, JVM class output,
M4 Max, best of three rounds of sixty, the two builds alternated; the "before" column is
the same call form on the pre-643 jar, where `%la-scaled-masked-softmax` IS the defun's
chain):

| per call, ms | before | after |
|---|---|---|
| `%la-scaled-masked-softmax`, scale + f64 mask | 14.00 | **1.62** |
| the same with an f32 mask | 13.87 | **1.60** |
| the same, scale only (no mask) | 4.23 | **1.52** |
| the same, mask only (no scale) | 11.55 | **1.57** |
| `%la-scaled-masked-softmax-grad`, scale + f64 mask | 15.32 | **3.38** |
| the same with an f32 mask | 15.12 | **3.40** |
| the chain's `linalg:div` alone | 2.12 | 2.12 |
| the chain's `linalg:where` alone, f64 mask (CPU) | 7.95 | 8.02 |
| the chain's `linalg:where` alone, f32 mask | 7.88 | **1.22** |
| the PLAIN `linalg:softmax` | 1.47 | 1.37 |
| the PLAIN softmax adjoint | 2.92 | 2.92 |

The f32 `where` row is a SIDE EFFECT rather than a fusion, and it is worth knowing about: the
mask is staged and ADOPTED on the second sight (`gemvF`'s rule -- the causal mask is one
array reached seventy-two times a step, and its upload is otherwise paid every time), and
once it is resident `whereF` is offered over it, because that member's offer rule counts any
resident operand. So the fold makes the chain's own fill a device member for whoever else
uses the same mask -- at the time of writing only at f32, since a double one was still
refused there. **todo-645 removed that refusal**, and the f64 row of this table is 8.0 ->
0.7 ms on the build that did ("The `where` mask's width" below).

**The step, batch 64** (`gpt-book-shapes-fast.lisp`, `--gpu --simd`, JVM class output, M4
Max with the machine to itself; `(t13 - t3) / 10`, three interleaved rounds):

| | before | after |
|---|---|---|
| wall a step, the three rounds | 5.502 / 5.495 / 5.637 | 4.664 / 4.615 / 4.662 |
| wall a step, median | 5.502 s | **4.662 s** |

Every after round beats every before round, which the wall's usual ±4% could not have
produced on its own; the per-call table predicts it exactly, at 36 forwards and 36 adjoints
a step: 36 x 12.4 + 36 x 11.9 ms = 0.87 s. **The loss series is byte-identical to the
previous build's at every step of all six runs** -- the fused kernel is the device chain's
bits, and the CPU select it replaces was exact.

### The `where` mask's width, and the rule that does not apply to it (todo-645, 2026-09-02)

`MetalGemm.whereF` opened with `if (m instanceof double[]) return false;` -- "a double
operand is a hard decline here like every other" -- and that is the wrong rule for THIS
operand. **A `where` mask is a PREDICATE, not a number.** `linalg:where`'s test is
`(/= m 0)`: any bit but the sign set, an integer test on the raw word. The width rule
exists because there is no `double` to compute WITH, and a mask is the one operand in the
library that is never computed with. todo-643's `pack_mask` had already been reading a
`double[]` mask as two `uint`s a cell (the low one first) for exactly that reason;
`where_f32` now binds its own mask as `device const uint*` and does the same test inline,
one word at f32 and two at f64, and the host stages and looks the mask up at ITS width
(`Call.lookupBytes` / `stageMask`, todo-643's). The values and the result are still
single, because they do enter arithmetic.

**It mattered because the mask a model builds is DOUBLE.** `torch:subsequent-mask` and
`torch:padding-mask` are built out of `linalg:ones` and `linalg:equal`, which build at
`linalg`'s default width whatever `torch:` is running at -- so every attention mask in
the library arrives as a `double[]`, and the decline sent the select to the CPU over a
MATERIALIZED score: 16.8 MB down, a scalar select, 16.8 MB back up.

**What is still reached after todo-643, and it is a whole class.** The fold takes a mask
only when it is a TRAILING BLOCK of the score. A CAUSAL mask is one (`(1 s s)` over
`(b s s)`), so the book's GPT never reaches `whereF` and **its step does not move**. A
PADDING mask is not: `torch:padding-mask` is `(batch 1 length)` over a
`(batch query key)` score, so `examples/llm-from-scratch/transformer`'s encoder and cross
attention -- and `chapter02/section5.lisp`'s `source-mask`, which is that same array --
decline the fold on SHAPE and fall back to `%la-scaled-masked-softmax`'s three members,
of which this `where` is one. Every masked attention that is not causal is in that class.

**Per call at the book's `(64 256 256)` score, f32**
(`.todo/123-gpu-acceleration/mtl-where-mask-width.lisp`, `--gpu --simd`, JVM class
output, M4 Max, thirty calls a round, best of three rounds; the two builds alternated and
the whole thing run three times, medians below -- every after round beats every before
round on every f64 row):

| per call, ms | before | after |
|---|---|---|
| `linalg:where`, f64 mask `(1 256 256)`, `-inf` fill | 7.83 | **1.53** |
| `linalg:where`, f64 mask, the adjoint's `0.0` fill | 7.80 | **0.73** |
| `linalg:where`, f64 mask `(64 1 256)`, a padding mask | 7.77 | **0.70** |
| `%la-scaled-masked-softmax`, f64 padding mask (fold refuses the SHAPE) | 14.07 | **1.87** |
| `linalg:where`, f32 mask `(1 256 256)` | 1.73 | 0.83 |
| `linalg:where`, f32 mask `(64 1 256)` | 1.30 | 0.67 |
| `%la-scaled-masked-softmax`, f32 padding mask | 3.03 | 1.80 |
| `%la-scaled-masked-softmax`, f64 CAUSAL mask (the fold takes it) | 1.80 | 1.77 |

Two things to read carefully.

- **The wall of an accepted member is no longer its device time.** Since todo-495 results
  are lazy and the command buffers asynchronous, so an accepted member is an ENQUEUE.
  The table above forces every result home with one `aref`, which adds a 16.8 MB download
  to every row equally; measured WITHOUT that read the same rows are 7.77 -> 0.00, 7.80 ->
  0.07, 7.70 -> 0.10 and 12.53 -> 0.47, and the two f32 rows and the causal row do not
  move at all. A member that ran on the CPU costs the same either way, which is why the
  before column of the f64 rows is the same number in both.
- **The f32 rows moved too, and that is an artifact of the probe rather than an effect.**
  In the BEFORE build every f64 row brings the score home, so the f32 row that follows it
  pays to put it back. In the AFTER build nothing comes home.

The last row is the control: the fold's own shape never went through `whereF`, its
kernels are untouched, and it did not move (1.80 -> 1.77, and 0.200 -> 0.200 unforced).
**The book's step does not move either, and could not**: its mask is the causal one, which
todo-643 folds, so `whereF` is never reached at all (`gpt-book-shapes-fast.lisp`,
`(t13 - t3) / 10`, two interleaved rounds: 1.86 / 2.12 s before against 1.83 / 1.80 after
-- inside the wall's usual +-4%, with no mechanism for a move).
The select is exact, so **`where` over an f64 mask is cell-for-cell what `where` over the
f32 copy of it is**, which the probe asserts at both mask shapes and
`MetalGpuTest.theResidentTierIsOfferedOnlyOverAResidentOperandAndLandsOnTheCpuKernelsBits`
pins at both widths over cells covering zero, NEGATIVE ZERO (false, `(/= m 0)`'s rule),
an ordinary value and a NaN (true).

#### What the fold's SHAPE decline costs on this backend (todo-650's Metal half, measured;
re-measured after the fix, 2026-09-03)

todo-650 was filed on the CUDA side: a padding mask is not a trailing block of the score,
so `%la-scaled-masked-softmax` declines and the defun's members run over a MATERIALIZED
score. The rule is `LinalgGpu.suffixLength` -- and its verbatim twin
`JvmGpuTemplate.softmaxMaskLength`, because the compiled path carries its own copy;
**a change to the acceptance condition has to change BOTH**. Neither is in `am.ik.gpu`, so
no backend can decline differently from another, and the counts below are structural.

Counted at the notebook's chapter-2 shapes (`d_model` 512, 6 blocks, 8 heads, `d_ff` 512,
batch 64, `max_length` 20, a 6638-token vocabulary --
`.todo/123-gpu-acceleration/transformer-book-shapes.lisp`, `--gpu --simd`, M4 Max), **per
step, exactly linear in the step count**:

| | accepted | declined ON SHAPE |
|---|---|---|
| `%la-scaled-masked-softmax` | 48 | **96** |
| `%la-scaled-masked-softmax-grad` | 48 | **96** |

which is the model's own structure: the 48 that pass are the decoder's SELF attention (6
blocks x 8 heads), whose `padding + subsequent` mask is `(batch len len)` and so a suffix;
the 96 that fail are the encoder's self attention and the decoder's cross attention, both
of which take `torch:padding-mask`'s `(batch 1 length)` -- extent 1 in the MIDDLE, which
`suffixLength` cannot drop. **The same 96 / 192 CUDA counts.**

**And the price WAS one whole score home per declined call -- until todo-650's fix, which
took the whole of it.** Against the same probe with the source mask materialized at the
score's own shape (`WIDEN=1`, so all 144 are accepted), JVM class output, `(t13 - t3) / 10`,
three interleaved rounds, M4 Max. **Both builds were made from `6e2e0557`** -- the BEFORE
one with `5baaf6ec`'s single code hunk reverted in place -- so the two arms and the two
builds all come from ONE tree (`.kb/measurement-probes.md`, rule 3):

| per step, with `5baaf6ec` REVERTED | declining (as shipped) | all accepted |
|---|---|---|
| host downloads | **879** | 687 |
| bytes downloaded | 194 MiB | 178 MiB |
| wall a step, median of 3 | 0.760 s | 0.743 s |

**192 extra downloads, which is exactly the 96 + 96 declines**, at 90 KB each -- one score
(64 x 19 x 19 f32 = 92416 B) per declined call, todo-650's own description. So the round
trip was real on this backend too, and todo-645 did NOT remove it: what todo-645 removed
was the CPU SELECT inside the fallback, which was the expensive half.

**`5baaf6ec` removed the other half, and more (2026-09-03).** The fix is one guard in
`codegen/jvm/JvmLinalgKernelCompiler` -- materialize an argument only where a host KERNEL
rung follows -- and it was landed and measured on CUDA. Nothing in `am.ik.gpu` or in
`gemm.metal` changed with it; the acceptance conditions (`LinalgGpu.suffixLength`,
`JvmGpuTemplate.softmaxMaskLength`) did not move by a byte. Re-measured here on the same
probe, same instrumentation, same tree:

| per step, with `5baaf6ec` IN | declining (as shipped) | all accepted |
|---|---|---|
| host downloads | **627** | **627** |
| bytes downloaded | 39.8 MiB | 39.8 MiB |
| wall a step, median of 3 | **0.684 s** | 0.709 s |

**The two arms are now identical to the BYTE** -- same count, same total, deterministic
across every round -- so the 192 round trips the shape decline used to cost are gone, and
the decline is free of downloads that the accept does not also pay. On the shipped arm the
fix is worth 252 downloads and 154 MiB a step (194 -> 40 MiB, -80%) and 0.076 s of a 0.760 s
step (-10%), which is the Metal counterpart of CUDA's 292 -> 4 / 30.8 -> 4.86 MB and 8.29 ->
7.70 s. The all-accepted arm dropped too (687 -> 627), because the widened probe still runs
declined members elsewhere in the step.

**And the sign of the accept-rule question flipped.** Before the fix, accepting every head
was worth 192 downloads and about 2% of wall; after it, accepting every head is worth
NOTHING and still pays for building the two widened masks, so the WIDEN arm is now the
slower of the two (0.709 against 0.684, a 3.5% gap that is inside the wall's usual +-4% and
is at best a wash). **The ceiling on changing `suffixLength` / `softmaxMaskLength` is
therefore no longer positive on this backend either**, which is the same verdict todo-650
reached on CUDA by a different route. The rule stays.

The download counts are structural and reproduce exactly: the BEFORE column re-derived
todo-645's original 879 / 687 to the unit, on a build made a day later. What produced
them is a counter added to `MetalGemm.download` for the run and taken out again -- there is
no shipped download counter, and the numbers above are what one costs to reproduce.

**No Metal-side todo was filed**: the counts are decided above `GpuDevice`, so there is no
Metal work item to open -- the change is `suffixLength` and its twin, or the shape
`torch:padding-mask` builds. This table is the Metal price for that decision, and after
`5baaf6ec` that price is zero.

(The 16.8 MB synthetic row above shows no round trip because its score is an adopted
resident `defparameter`; that is the shape of the probe, not of a training step. Measure
this one on a model.)

**Nothing else this backend refuses on width alone is reached by the reasoning**, and the
survey is short because the question is not "is the operand read as bytes" but "is it
read as a NUMBER". Every other double operand -- `zip`, `scal`, `fold`, `adam`, `copy`,
the GEMM and the GEMV, the fused rows -- is arithmetic on the value itself. `where`'s
mask is the only predicate in the library, and the comparison members (`greater`,
`equal`, ...) PRODUCE masks rather than consuming them, at the operand's own width.

### Layer-norm's affine on Metal: built, measured, NOT kept (todo-646, 2026-09-03)

todo-634 folded `torch:layer-norm`'s `* weight + bias` into the normalization as a
two-output member pair and built the kernels in `gemm.cu` only; this backend answered
`false` at both widths, and `.todo/646` was the measurement that decides whether the fold
pays here. **The MSL pair was written, pinned bit-identical and measured. It is worth a
quarter of the adjoint PER CALL and nothing at all in the step, and it is not kept.**

**Why the adjoint's decline was so expensive, which the item predicted wrongly.** The item
expected the forward to be where the money was -- the affine's two extra members are
broadcast multiplies against a `(len)` operand and the book's `(16384 384)` activation
clears `MIN_STRIDED_ELEMENTS`, so they ARE device members and the fold removes two memory
passes and two command buffers. That is right and it is worth half a millisecond. The
ADJOINT was worth six times that, for a reason nobody named:
`%la-layer-norm-affine-grad` is spelled over `%la-layer-norm-grad-norm` (todo-644's
sibling that answers `(dx norm)` from one pass), and **that member has no interception of
its own on either backend** -- it exists only as a Lisp defun. So the decline landed on
the defun's own twenty-odd `linalg:` members, among them the `linalg:sum ... :axis` folds
this backend REFUSES at every size, each of which came home. Same asymmetry todo-643
measured, one rung further down.

**Per call, at the book's own shapes** (`.todo/123-gpu-acceleration/mtl-layer-norm-affine.lisp`,
`(16384 384)` `#f`, `--gpu --simd`, JVM class output, M4 Max, 40 reps, best of 4 rounds,
the whole probe run twice; taken on the tree described below):

| forced home, ms | declined | the fused pair |
|---|---|---|
| `%la-layer-norm-affine` | 3.93 / 3.48 | **3.28 / 3.25** |
| `%la-layer-norm-affine-grad`, no `old` | 13.75 / 13.78 | **10.78 / 9.40** |
| `%la-layer-norm-affine-grad`, onto `old` | 15.18 / 14.68 | **10.93 / 9.85** |
| `%la-layer-norm` (untouched, the control) | 2.73 / 2.10 | 2.38 / 2.23 |
| `%la-layer-norm-grad` (untouched, the control) | 10.33 / 8.05 | 9.08 / 8.48 |

Thirteen layer-norms a step, so that is 45-50 ms of member time a step. **The step does not
see it** (`gpt-book-shapes-fast.lisp`, `(t23 - t3) / 20` to halve the noise, four rounds
with the two builds interleaved inside each round):

| | declined | the fused pair |
|---|---|---|
| wall a step, the four rounds | 1.663 / 1.649 / 1.698 / 1.873 | 1.623 / 1.645 / 1.760 / 1.882 |
| wall a step, median | **1.680 s** | **1.702 s** |

A coin flip, and the median falls on the wrong side. **The measured member time is not on
the critical path**: under asynchronous command buffers the work removed here overlaps
host work that remains, so removing it buys the step nothing.

**The trap this walked into, recorded because it nearly landed the change.** The FIRST step
measurement of the same two builds said 1.798 -> 1.633 s, 9.2%, every round beating every
round. It was taken before `5baaf6ec` ("stop materializing an argument no host kernel is
going to read") was merged in. That item stops the compiled call site dragging a
device-only fused member's arguments home before falling through to the defun, which for
this member was three 25.2 MB downloads a call -- and it took the DECLINING build from
1.798 to 1.680 s on its own. So the 9.2% was almost entirely the generic fix's, measured
against a build that did not have it yet, and attributing it here would have been wrong by
a factor of five. This is CLAUDE.md's semantic-conflict warning in its literal form: two
changes that each remove the same host round trips, one specific and one general, and the
general one landed first. **Re-take a step number after every merge that touches the path,
not only after one that conflicts textually.**

**What would reopen it.** The kernels are correct and the equality held on the first run,
so this is a decision about worth and not about feasibility. It becomes worth building
again when the step is DEVICE-bound at these shapes rather than bound by what overlaps --
a larger batch or model, or after enough of the remaining host members move -- because
then the 45-50 ms a step stops being slack. The way to check that without writing the
kernels again is the upper bound (`.kb/measurement-probes.md`): the per-call table above
already IS the bound, so re-take the step, not the members.

**Checked against `.kb/measurement-probes.md`**: trap 2 by printing both a forced-home and
an enqueue-only column and deciding on the forced one; trap 3 by taking the step at all,
which is what caught the `5baaf6ec` attribution -- the probe's operands are
`defparameter`s and the step's are not; trap 4 does not arise, since neither build
back-to-backs a call behind a host gap the other does not. Conditions: results forced home
with one `aref` in the first table and unread in the second, no gap inserted between
calls, `--simd` on and `RONTOLISP_THREADS` at its default on a 40-core M4 Max, both builds
compiled from the same tree and interleaved within each round.

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

**Fifty-seven `linalg:` members and one outside it.** By round trip: `linalg:dot` over two
packed rank-2 operands of the same width (hence `matmul` at rank 2 and `solve`
transitively); `%la-matmul-nd`, the STACKED product behind `matmul` at rank >= 3, and its
two TRANSPOSED siblings `%la-matmul-nd-ta` / `%la-matmul-nd-tb` ("The transposed product",
below); the
twelve element-wise `exp` `log` `tanh` `sin` `cos` `tan` `asin` `acos` `atan` `sinh` `cosh`
`erf`; the STRIDED tier -- `add` `sub` `mul` `div` `maximum` `minimum` at a BROADCAST shape
only, `sum` `amax` `amin` in their `:axis` form only, `transpose` in its axes form only;
and `%la-rng-fill`, the seeded generator's fill behind `rand` / `randn` / `uniform`, the
only member with NO operand. Over a RESIDENT operand: the resident, index and copy tiers
listed under residency. The FUSED tier (todo-499, todo-629): `linalg:softmax` and
`linalg:log-softmax` in their `:axis` form over the last axis, and the nine internal
members `torch.lisp` spells its compositions through -- `%la-softmax-grad`,
`%la-log-softmax-grad`, `%la-gelu`, `%la-gelu-grad`, `%la-layer-norm`,
`%la-layer-norm-grad`, `%la-dropout-mask`, and (todo-641) `%la-scaled-masked-softmax`
with `%la-scaled-masked-softmax-grad`, over the last axis and a mask that is a trailing
block of the operand only -- offered by the rule of the chain each
replaces (the map's threshold where a libm call is in it, the fold's otherwise, or a
resident operand). Outside `linalg:`: `vec:matvec`, installed by `LinalgGpu.installVec`
from the VEC library's own lazy-load hook, because the two libraries load independently and
a program may reach either first.

**Nothing else is `defineFunction`ed**, and that is an assertion rather than a remark:
`#'linalg:outer`, `#'linalg:norm`, `#'linalg:matmul` and nine
more still print `#<lambda>` under the flag, which is each tier's own dead-flag guard
from the other side.

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

**The size objection does not survive measurement -- but the numbers have moved a long way
since it was first taken** (re-measured 2026-09-03, todo-656). What is REPLACED here is
"~300 KB bigger than a `--simd` one, and `sin`/`cos`/`tan` are the place to shrink": both
were true when the PTX held a handful of kernels, and neither is now. Measured on one tree,
the same program compiled twice, `--simd` against `--simd --gpu`: **2,571,220 against
172,427 bytes, so `--gpu` adds 2,398,793**, and it divides as

| part | bytes in the class | share |
|---|---|---|
| `gemm.ptx`, embedded verbatim | 1,885,029 | 78.6% |
| the 22 `GPU_CLASSES` class files, base64 (287,225 raw) | 382,968 | 16.0% |
| `gemm.metal`, verbatim | 71,701 | 3.0% |
| the bridge `JvmGpuTemplate`, base64 (42,399 raw) | 56,532 | 2.4% |

**The blob is the PTX, and the PTX is the FUSED ROW FAMILY**: `softmax`, `softmax_grad`,
`log_softmax`, `layer_norm*` and `gelu*` are 20 of the module's 58 entries and 1,548,866
bytes -- **82.2% of the PTX and 64.6% of everything `--gpu` adds**, the four softmax
entries alone 663 KB. The transcendentals this paragraph used to name are not entries at
all; they are op codes inside `map`, and `map_f32` + `map_f64` together are 62,525 bytes,
3.3% of the PTX. So if the blob ever has to shrink it is a fused-row question and nothing
else is worth opening. What did NOT change is the conclusion: every one of those kernels is
there because a measurement put it there, and the objection is still answered by the fact
that a `--simd` class already embeds a 62 KB `JvmSimdVectorTemplate` for the same reason.

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

### The offer is decided twice, and what pins the two (todo-654, 2026-09-03)

The library travels, but the DECISION to offer a shape to it does not: `eval/LinalgGpu` is
what the interpreter runs and `codegen/jvm/JvmGpuTemplate` is the copy the compiled program
carries, and both sit ABOVE `am.ik.gpu`, so neither backend can correct a disagreement
between them. A shape one accepts and the other declines is a program that runs
`java -jar` and `-o out.class` down different paths, at the same inputs, with nothing
failing.

**The pin is `codegen/jvm/GpuOfferDifferentialTest`**, and it is deliberately not thirteen
per-helper assertions. The two files share thirteen predicates -- twelve under one name each
(`batchStride`, `bcast`, `bcastShape`, `bcastStrides`, `copyInto`, `foldAxis`, `map`,
`resident`, `rowMajorStrides`, `sameShape`, `scale`, `zip`) and one under TWO,
`LinalgGpu.suffixLength` against `JvmGpuTemplate.softmaxMaskLength`, which is why
`grep -rn suffixLength` reads as if the mask rule were written once. A helper-name net never
had that pair in it, and thirteen assertions would say nothing about a fourteenth. So the
question is asked from OUTSIDE both paths instead: one set of operands, each path's own call
shape, and the two must agree on accept versus decline and, where they accept, answer the
same bits. The shapes are chosen at the accept BOUNDARY, not for coverage -- a mask that is
a trailing suffix and one whose middle axis is extent 1 (the `(batch 1 key)` shape .todo/650
was filed for), an exactly-equal pair, a rank mismatch, a fold on the last axis and one that
is not, a resident operand and a fresh one, both widths -- and a census assertion fails the
run if the table did not both accept and decline, which is what stops a machine that turned
everything down from agreeing vacuously.

**Only one of its two halves runs on a GPU-less machine, and that is a real gap.** The
member SET differential is device-free: every name the compile path claims
(`JvmLinalgGpu.qualifiedMembers()`) is bound to a sentinel and handed to
`LinalgGpu.install`, which OVERRIDES what it accelerates -- a name still bound to the
sentinel is one the interpreter does not accelerate, and a name the interpreter accelerates
that the compile path never claimed makes `install` throw with the member in the message. The
SHAPE half cannot be asked without a device, and the reason is structural rather than a
choice: on both paths a shape decline and a no-device decline are the same `null`, and the
compiled bridge fuses `!Gpu.available()` into the same `||` as the shape tests. `Probe.DEVICE`
is a static final holder and `GpuDevice` is `sealed permits CudaGemm, MetalGemm`, so no test
can stand a device up to separate the two.

#### Closing the gap was priced and DECLINED (todo-656, 2026-09-03)

Two ways were on the table -- (a) a say-yes-to-everything third `GpuDevice` in `am.ik.gpu`,
(b) hoisting each bridge member's `!Gpu.available()` out of its shape expression and
exposing the shape decision on its own -- and the item filed both as SIZE decisions,
because whatever either adds travels in every compiled `--gpu` program. **Size is not the
axis.** Against the 2,398,793 bytes `--gpu` already adds (the table above), a stand-in
implementing all 71 `GpuDevice` methods with constant bodies compiles to 5,326 bytes, 7,101
base64, **0.30%**; and DOUBLING the bridge outright -- a ceiling far above any predicate
surface (b) would add -- is 2.4%. Neither is a size decision. What decided it is the
CEILING on what CI would additionally pin, and it collapses on the opportunity side twice
over:

- **A divergence cannot MANIFEST without a device.** Every entry point in `am.ik.gpu.Gpu`
  is `device != null && ...` over `Probe.DEVICE` (read in the code, not inferred), so on a
  GPU-less machine nothing is ever accepted and both paths run their scalar fallback
  whatever their predicates say. A `JvmGpuTemplate` shape rule that disagreed with
  `LinalgGpu` produces a wrong answer only on a machine where this test already runs. The
  gate does not leave the defect unprotected; it defers detection to the first machine on
  which the defect is observable at all.
- **The population of commits that could carry one undetected is empty.** 23 commits have
  touched `JvmGpuTemplate` or `LinalgGpu` and 18 touched both; of the 5 asymmetric ones two
  are each side's first build and one is a merge, and the remaining two (`fa343e55`,
  `198ebcd3`) are Metal work. Every one of the 23 is a `--gpu` topic commit landing
  measurements in THIS file -- taken on a device. Over the same window 8 distinct days
  carried a device-session commit, longest gap 6 days, which bounds the latency the gate
  costs.

**And a stand-in would pin LESS than the shape half pins today**, which is the argument
that would hold even if the two above did not. 7 of the 43 boundary cases are over a
RESIDENT operand -- including all four `-1` reshape cases todo-663 added, the one time this
pin has caught anything. A device that answers `true` from every kernel without touching
memory answers `resident(host) == false`, so all 7 decline on BOTH paths and agree
VACUOUSLY (`.kb/test-execution.md`, "A test that never ran the mechanism it asserts on").
Keeping them alive means modelling `DeviceResidency`, the lazy stubs and
`written`/`materialize` in the stand-in -- a second implementation of exactly the mechanism
the paragraph above refuses to fork. The bits half goes either way: an unwritten
destination is zeros on both paths, so the `isEqualTo(bitsOf(accepted))` that today
separates two accepts becomes `0 == 0`.

**(b) fails on the axis the test was designed for.** A parallel shape-predicate surface
makes the test assert PREDICATE against PREDICATE instead of OFFER against OFFER, so the
predicate can drift from the offer it describes -- a new vacuity of the same shape the
census assertion exists to catch -- unless all 25 members are rewritten to call their own
predicate, which is a refactor of the file that travels, for a test.

So neither was built, the shape half stays device-gated, and
`GpuOfferDifferentialTest`'s own javadoc now says it does not run on CI and why, so a green
CI run is not read as covering the shape rule.

**The thirteen bodies, read against each other.** Four are word-for-word once the two
representations are allowed for -- `bcastShape`, `bcastStrides`, `rowMajorStrides` and
`batchStride` (whose declarations sit in a different order and whose arithmetic does not),
and so is the loop inside `suffixLength` / `softmaxMaskLength`. The rest say the same thing
differently, always because a `LispFloatArray` carries `dims()` and `storage()` where a
compiled array carries a `[rank, dim...]` header: `sameShape` compares `Arrays.equals` on one
side and rank-then-length-then-each-dim on the other; `resident` is one hop of indirection
apart; `copyInto` passes a `{0, totalSize}` span where the bridge passes
`{1 + rank, length - 1 - rank}`, which is the same span offset by the header; and `map`,
`bcast`, `zip`, `scale` and `foldAxis` make the same decisions in the same order with three
guards the bridge needs and the interpreter does not -- `rank < 1`, `count < 1` and a
`total + 1 + rank` overflow bound, because a header can describe a rank-0 or empty array. **No
pair is a different predicate.** The three extra guards can only bite over a rank-0 or
zero-extent operand, and every tier that reaches them wants a RESIDENT operand, which such an
array can never become -- nothing uploads one. The same closes the one arithmetic near-miss:
`suffixLength` answers `0` only when a mask extent is 0, the interpreter declines `< 1` and
the bridge `< 0`, and a zero extent in the mask forces the matching zero in the operand, which
both paths decline for having no rows before either sees the mask at all.

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
| the two paths' OFFER, differentially -- the member set on every machine, the shapes on a device | `codegen/jvm/GpuOfferDifferentialTest` |
| the flag is value-less, the REPL pair, the `.wasm` refusal | `cli/CliOptionsTest`, `cli/RontoLispCliTest` |

**The dead-flag guard is the load-bearing one**, as it is for `--blas`: every numeric
assertion would pass just as well on the scalar defun, so `#'linalg:dot` printing
`#<function LINALG:DOT>` under the flag and `#<lambda>` without it is the assertion that
fails when the flag is DEAD. It is one assertion per accelerated member, plus the
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
- **The tests that assert on device memory hold a `@ResourceLock`.** Five of them
  (`...FreesEveryBufferItAllocates`) ask the POOL, not the device, since .todo/481 --
  immune to a sibling process, whether a second surefire fork or anything else on the
  machine. Two more (`theResidentSetIsBoundedByItsBudgetAndAReleaseGivesTheMemoryBack`
  and the lazy-results eviction test) still ask `cuMemGetInfo` against the 1.5 GB bound,
  because their "before" is measured right after a release rather than a warm-up call and
  the pool can legitimately read near zero there. Every leak run is sized so a real leak
  is 2-8x whichever bound it uses.
- **A test that asserts an exact `residentBytes()` must KEEP ITS ARRAYS REACHABLE, and a
  process-wide `dirtyCount()`/`backingCount()` diff around one call is not that call's own
  effect** -- two invariants a test can silently depend on that are really the JVM's
  discretion, both written up once in `.kb/test-execution.md` rather than per backend.
  Concretely here: the reachability fence sits in
  `MetalGpuTest.theStridedCopyIsTheCopyMembersOverAResidentOperandAndAScaleRunsInPlace`
  (it bit that test once in three runs, 2026-09-02) and the CUDA suite's three strict
  `residentBytes()` assertions were checked against the same rule; the per-handle
  `DeviceResidency.dirty(Object)` / `.backed(Object)` predicates
  (`GpuThresholds.isDirty`/`.isBacked` for tests outside the package) exist because
  `eval.LinalgGpuTest.aDeviceResultStaysOnTheDeviceUntilTheHostFirstReadsIt`'s
  `dirtyCount()`/`backingCount()` diff failed in a full-suite run (2026-09-02, alongside
  `.todo/644`, which the failure was unrelated to) and now asks about its own result's
  handle instead of the shared cache.
- **A test whose shape does not clear the threshold that gates the mechanism it asserts
  on runs nothing, and passes.** The general rule and what to do about it are in
  `.kb/test-execution.md`, "A test that never ran the mechanism it asserts on"; the sweeps
  that produced it are below, one per backend -- and note that they found DIFFERENT tests
  vacuous, because the thresholds differ, so neither answers for the other.
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

### The test-side sweep on Metal (2026-09-03)

`.todo/655` asks whether an accept rule and the shapes that actually reach it were ever
compared. Its PRODUCTION half is that item's; this is its TEST half, swept on an M4 Max
with the device in force. What the machine answers, for the record, since every number
below is relative to it:

```
Apple M4 Max (Metal, unified memory, 107 GB working set)
supportsDouble=false  lazyResultsPay=true
work 4194304  map 131072  strided 262144  fold MAX  fused 131072  rng MAX  matvec 2097152
```

**Note the first trap in that table.** `Gpu.worth(n, m, p)` -- the probe-free predicate --
answers `true` at 64 cubed, because it applies `POOLED_MIN_WORK` (131072) and not the
`Probe.MIN_WORK` the offer actually applies (4194304 here). So "the shape is above the
threshold" can be checked, believed, and wrong. `Gpu.multiply` is the only authority, and
`GpuThresholds.minWork()` the number to size against.

Five findings, each established by MUTATION (put the old constant back with the new census
in place and watch the value assertions still pass while only the census fails) or, where
the mechanism runs in another loader, by measuring the offer directly:

1. **`codegen/jvm/GpuOfferDifferentialTest` FAILED on Metal**, at `e6d6e3ae` -- not
   vacuously green, red, and invisible because every CI runner this project has is
   GPU-less. Its `BIG` is `2 * max(map, strided, fold, fused)`, and `fold` is
   `Long.MAX_VALUE` here, so `BIG` wrapped to `-2`, the `Math.max(2, ...)` under it handed
   back a batch of 2, every "big enough to be offered" operand became 1024 elements, and
   the `warmed()` operands' warm-up assertion fired. Fixed by taking the maximum over the
   thresholds that are FINITE: a tier no size reaches must not be what an operand is sized
   for.
2. **`JvmLinalgGpuAccelCompilerTest.theFusedTierRunsOnTheCompiledBackendAndLandsOnTheChainsBits`
   was the unfixed twin of todo-495's bug.** It still sized its rows off
   `foldMinElements()`; the same overflow clamped it to the floor of 256, and 256 x 384 =
   98304 is under the fused threshold of 131072. Every `array-equal` in it printed `T`
   from the defun against itself. **Fixing it exposed a real divergence**: with the tier
   actually running, `linalg:log-softmax` against the chain it replaces answers NIL,
   because that chain ends in a `linalg:log` over the ROW SUMS -- an array of `rows`
   elements, three orders of magnitude under the element-wise threshold -- which runs on
   the host while the fused kernel takes its log on the device. Two different logs. That
   line is now pinned as a bound rather than as bit-identity, which is what it can be.
3. **Every hard-coded product shape in `eval/LinalgGpuTest` declined here.** Measured with
   `Gpu.multiply` on the real device: 64-cube 262144, 60x70x50 210000, 2 x 64-cube 524288,
   the rank-4 393216, the rectangular slab 420000 -- all under 4194304, and the
   default-width ones declined for their width besides. Four tests
   (`theMatrixProductMatchesTheScalarOracleOnExactInputs`,
   `theSingleFloatProductMatchesTheScalarOracleOnExactInputs`,
   `theStackedProductMatchesTheScalarOracleAtEveryBatchShape`,
   `everyCombinationOfTheThreeFlagsRunsAnExactProgramToTheSameOutput`) were the defun
   against itself. The class had defined `SIDE` off the threshold in force for exactly
   this reason, and these four did not use it.
4. **`theClipNormFoldsInBlocksOnTheDeviceCloseToTheSequentialSumAndReproducibly` reached
   the device on NEITHER backend.** `Gpu.sumSquares` is offered over a resident operand
   only -- and so is the scalar `Gpu.scale` that built its gradient, which therefore
   declined over the fresh operand above it. Measured: byte-identical output, zero
   residency lookups. Not a Metal finding; it was vacuous everywhere. The gradient is now
   built through a broadcast add, which is what makes an operand resident here.
5. **`eval/LinalgGpuDeclineTest`'s shapes are fixed and must stay fixed** -- it is the half
   a GPU-less CI runner executes, so it may not size itself off a machine's thresholds --
   but five of its comments claimed "on a GPU machine the device really is asked", which is
   false on Metal for every one of them. The assertions are sound decline tests; the
   comments now say so.

The tests already carrying a residency census (the fused, resident and index tiers, and
the GEMV) were checked and are sound. `am/ik/gpu/MetalGpuTest` was swept test by test and
found clean: it derives every shape from a threshold accessor and asserts the accept /
decline boolean of every member it calls, so it cannot fail vacuously -- two strict
`residentBytes()` assertions were missing their reachability fences, which is the other
hazard above and is now fixed.

**What was NOT swept**: whether a claim `GpuTest` makes has a Metal sibling at all. That
suite is gated on a double-capable device and its 57 tests skip in full on every Mac.
That was `.todo/662`, and it is the section below.

### What `GpuTest` claims, and where Metal answers it (todo-662, 2026-09-03)

The sweep above asked "does the shape reach the mechanism". This asks the prior question:
**is there a test on this backend at all.** `am/ik/gpu/GpuTest` is gated on a DOUBLE-capable
device, so its 57 tests skip in full on every Mac; `am/ik/gpu/MetalGpuTest` had 38 under
names that do not correspond, and the two lists had never been compared. A pin inside a
device gate is not "covered on machines that have that device" -- it is covered on ONE
backend.

The comparison, per `GpuTest` test, taken on an M4 Max with the device in force. Every
"not a member" below was established by CALLING the member over a RESIDENT operand and
reading the answer, not by reading the code.

| `GpuTest` claim | on Metal |
|---|---|
| `theCheckedInPtxLoadsAndTheKernelComputes` | covered -- `theCheckedInMetalKernelsCompileAndTheProductComputes`, with `theCheckedInMetalSourceIsTheArtifactTheLoaderExpects` for the artifact half |
| `theSingleFloatKernelComputesTheSameExactValues` | covered -- the same test; `#f` is the only width here |
| `bothAllocatorRoutesComputeTheSameProduct` | **not applicable** -- there is no allocator switch on unified memory. The two-routes claim is `bothProductRoutesComputeTheSameProduct` (MPS against the tiled kernel) |
| `anInexactProductAgreesWithTheScalarOracleToTheWidthsOwnTolerance` | covered -- same name |
| `everyOperandIncludingTheResultIsReadFromItsOwnOffset` | covered -- same name |
| `aRectangularProductUsesAllThreeDimensions` | covered -- same name |
| `anOperandTooBigForOneCriticalCopyIsSplitAndStillAgrees` | **not applicable** -- `CRITICAL_CHUNK_BYTES` is `CudaGemm`'s; this backend stages a heap segment whole and there is no bound to straddle |
| `everyDeclineConditionStillDeclinesWithADevicePresent` | covered -- same name |
| `everyElementWiseMemberComputesItsOwnFunction` | covered -- same name (the twelve libm members; the resident tier's four are in the tier's own test) |
| `anElementWiseMapReadsAndWritesFromItsOwnOffset` | covered -- same name |
| `everyElementWiseDeclineConditionStillDeclinesWithADevicePresent` | covered -- **by `GpuDeclineTest`, which is UNGATED and therefore runs here WITH the device**, and whose `n` is `mapMinElements() * 2`, above this backend's map floor. That is an accident of sizing worth knowing about: it is what makes the device-free suite a device-present suite on a Mac |
| `aRunOfElementWiseMapsFreesEveryBufferItAllocates` | **was a gap** -- `aRunOfElementWiseAndStridedCallsSettlesThePoolRatherThanGrowingIt` |
| `aBroadcastBinaryOpMatchesTheScalarOdometerWalk` | covered -- `theStridedTierIsBitIdenticalToTheScalarOracle` is the same claim at the same dims and strides over inexact data. (Listed as a suspected gap when this item was raised; the name diff was wrong) |
| `aStridedGatherIsThePermutedCopy` | **was a gap at rank 3** -- the rank-2 transpose is in `theStridedTierIsBitIdenticalToTheScalarOracle`; the (0 2 1) walk every attention head asks for is `aStridedGatherIsThePermutedCopyAtRankThree` |
| `anAxisFoldIsTheDefunsOwnSequentialFold` | **was a gap** -- the fold is not a member for its SIZE here, but it IS one over a resident operand at any `inner`, and only `inner == 1` was pinned. `anAxisFoldOverAResidentOperandIsTheDefunsOwnSequentialFoldAtEveryInnerStride` |
| `everyStridedOperandIncludingTheResultIsReadFromItsOwnOffset` | **was a gap** -- same name |
| `everyStridedDeclineConditionStillDeclinesWithADevicePresent` | covered -- `GpuDeclineTest`'s ungated version builds 4096 x 64 = 262144 output elements, which is EXACTLY this backend's strided floor, so it runs with the device present here. Its FOLD conditions did not: for their size they decline anyway, and they are now asked over a resident operand in `everyResidentTierDeclineConditionStillDeclinesWithADevicePresent` |
| `aRunOfStridedCallsFreesEveryBufferItAllocates` | **was a gap** -- folded into the element-wise pool run above |
| `theGeneratorFillIsBitIdenticalToTheSequentialWalk...` | **not applicable** -- the fill is not a member (`rngMinElements()` is `Long.MAX_VALUE`); `theDropoutMaskStaysDeclinedHere` pins the refusal |
| `aRunOfGeneratorFillsFreesEveryBufferItAllocates` | **not applicable** -- same |
| `aMatrixByVectorProductIsTakenOnlyOnceItsMatrixHasBeenOfferedTwiceUnwritten` | covered -- same name |
| `aSingleFloatMatrixByVectorProductLandsOnTheDoubleAccumulatedOracle` | covered -- `...WithoutADouble`, which is the stronger claim here |
| `aDoubleMatrixByVectorProductAgreesWithTheOracleToAFewUlps` | **not applicable** -- MSL has no `double` |
| `everyMatrixByVectorOperandIncludingTheResultIsReadFromItsOwnOffset` | covered -- same name |
| `everyMatrixByVectorDeclineConditionStillDeclinesWithADevicePresent` | covered -- same name |
| `aRunOfMatrixByVectorProductsFreesEveryBufferItAllocates` | covered -- `...SettlesThePoolRatherThanGrowingIt` |
| `aRunOfSuccessfulProductsFreesEveryBufferItAllocates` | covered -- `aRunOfCallsSettlesTheBufferPoolRatherThanGrowingIt` |
| `aDeclinedProductCostsTheDeviceNothing` | **was a gap** -- `theSameProductRepeatedIsTheSameAnswerAndADeclinedOneCostsThePoolNothing` |
| `theSameProductRepeatedIsTheSameAnswer` | **was a gap**, and it matters more here than on CUDA: above the MPS threshold the route is Apple's library rather than a kernel of ours, and a library free to pick a decomposition per call would show here and nowhere else. Same test |
| `aBatchedProductIsThePerBatchProductOfEachSlab` | covered -- `aBatchIsTheSameSlabsRunOneAtATime` and `aBatchAboveTheMpsThresholdAddressesEachSlabByItsOwnOffset` |
| `aBatchIsBitIdenticalToTheSameSlabsRunOneAtATime` | covered -- same |
| `everySingleFloatProductKernelLandsOnTheSameFusedFold` | **not applicable** -- there is no family of per-shape kernels with an `fma` contract here: one tiled kernel and MPS, whose fold order is Apple's. That the two agree bit for bit is `bothProductRoutesComputeTheSameProduct` and the four shapes of `aTransposedOperandIsReadInPlace...` |
| `aTransposedOperandIsReadInPlaceAndFoldsOntoTheUntransposedProductAtBothWidths` | covered -- same name at the one width |
| `aBroadcastOperandIsAZeroStrideAndReadsTheSameSlabEveryBatch` | covered for the RIGHT operand; the left-operand broadcast (one activation against a stack of weights) **was a gap** and is in `aBatchedProductReadsEveryOperandFromItsOwnOffsetAndBroadcastsEitherSide` |
| `aBatchedProductReadsEveryOperandFromItsOwnOffset` | **was a gap** -- same test |
| `everyBatchedDeclineConditionStillDeclinesWithADevicePresent` | **was a gap** -- and a clean example of the sweep's own rule: `GpuDeclineTest`'s batched shapes are 8 x 64 x 64 x 64 = 2097152 units of work, under this backend's floor of 4194304, so every one of them declined for its SIZE and the enumeration pinned nothing here. The new test asserts an accepted baseline at the same shape first |
| `anOperandUploadedOrProducedByARecentCallIsNotUploadedAgain` | covered -- EAGERLY this backend keeps only a GEMV's matrix, which `eagerlyOnlyTheMatrixOfAnAcceptedGemvIsKeptResident` pins as the deliberate opposite rule; LAZILY the chain-is-a-hit census is in `aLazyResultStaysOnTheDeviceUntilTheHostFirstReadsIt` |
| `aWrittenHostArrayIsUploadedAgainAndTheAnswerFollowsTheWrite` | covered -- `aWriteToALazyResultBringsItHomeFirst` and the write half of the GEMV residency test |
| `theResidentSetIsBoundedByItsBudgetAndAReleaseGivesTheMemoryBack` | covered -- `...GivesTheSlabsBack` |
| `aCollectedHostArrayTakesItsResidentCopyWithIt` | covered -- same name |
| `aLazyResultStaysOnTheDeviceUntilTheHostFirstReadsIt` | covered -- same name |
| `aWriteToALazyResultBringsItHomeFirst` | covered -- same name |
| `anEvictedOrReleasedLazyResultIsDownloadedNotDropped` | covered -- same name |
| `aStubResultAllocatesNoHostArrayUntilTheHostFirstReadsIt` | **was a gap** -- same name. The stub machinery is `DeviceResidency`'s and works here unchanged; since todo-495 it is what every compiled `--gpu` program's results ARE on this backend |
| `aWriteThroughAStubLandsInItsBackingAndTheStubIsUploadedFromIt` | **was a gap** -- same name |
| `anEvictedReleasedOrEagerStubIsDownloadedIntoABackingNotLost` | **was a gap** -- same name |
| `aCollectedStubTakesItsBackingWithIt` | **was a gap** -- same name |
| `aDeviceMemberUpdatingAnArrayInPlaceLeavesItResidentAndAuthoritative` | covered -- its vehicle there is `rngFill`, which is not a member here; the claim is the in-place scale in `theStridedCopyIsTheCopyMembersOverAResidentOperandAndAScaleRunsInPlace` |
| `theResidentTierIsOfferedOnlyOverAResidentOperandAndLandsOnTheCpuKernelsBits` | covered -- same name |
| `everyResidentTierDeclineConditionStillDeclinesWithADevicePresent` | **was a gap** -- same name. `GpuDeclineTest` asks the tier with NOTHING resident, where every member declines for that alone and the bounds checks behind it are never reached |
| `theStridedCopyIsTheCopyMembersOverAResidentOperandAndAScaleRunsInPlace` | covered -- same name |
| `theIndexTierIsOfferedOnlyOverAResidentOperandAndCopiesTheCpuKernelsBits` | **not applicable** -- see the correction below |
| `aDivisionByAPowerOfTwoIsTheExactReciprocalsMultiplyAtBothWidths` | **was a gap**, and the sharpest one: `Gpu.normalPowerOfTwo` requires the divisor and its reciprocal to be normal at `float` precisely so the rewrite is exact "on a backend that computes in `float` (Metal)", and that argument was asserted only where it is not needed. `aDivisionByAPowerOfTwoIsTheExactReciprocalsMultiply` |
| `theFusedTierLandsOnTheComposedDeviceChainsBitsAtBothWidths` | covered -- `theFusedTierLandsOnTheComposedDeviceChainsBits` and `theScaledAndMaskedSoftmaxLandsOnTheComposedDeviceChainsBits`; the affine pair is not a member (below) |
| `theLibmFreeFusedMembersAreTheSequentialReferencesBits` | **was a gap** -- same name, closed by todo-665. It HOLDS, first run, for every member that is both libm-free and a member here (layer-norm, its adjoint onto a fresh and an accumulated gradient, the softmax adjoint), so no bound was needed in place of the equality: the row fold on this backend is SEQUENTIAL in software binary64, not a threadgroup tree. "The libm-free members against a SEQUENTIAL replay" above |
| `everyFusedDeclineConditionStillDeclinesWithADevicePresent` | **was a gap** -- same name. `GpuDeclineTest`'s fused enumeration is 8 rows of 16, under every threshold on every backend |
| `theSumOfSquaresFoldsInBlocksAndIsReproducible...` | **not applicable** -- see the correction below |

Counted: **31 covered, 8 not applicable, 18 gaps** -- 17 closed by 15 new tests in
`MetalGpuTest` (53 tests, from 38), and the eighteenth by todo-665 (54 tests).

**The correction this produced.** The bullet under "What is deliberately NOT here" had said
that todo-495, by flipping `lazyResultsPay` on this backend, made the index tier and the
clip norm "reachable" and left only their pinning missing. That is false and was read as
current by two later sweeps. `MetalGemm.take`, `takeF`, `scatter`, `scatterF`, `sumSquares`
and `sumSquaresF` return `false` / `null` unconditionally -- the kernels were never
written -- so the mode was never what stood in the way. Measured over a RESIDENT operand
(512 x 384 table, above the map floor, `Gpu.resident` true): `takeRows` false, `pick`
false, `scatterRows` false, `sumSquares` null. Layer-norm's affine pair is the third
member of this shape and is likewise declined at every size. All three are now pinned as
declines over a resident operand in
`theIndexTierTheClipNormAndTheAffinePairAreNotMembersHereAndDeclineOverAResidentOperand` --
over a RESIDENT operand specifically, because that is the only state that separates "not a
member here" from "not resident yet", and because a round that adds the kernels has to come
to that test to change the answer.

**What a DECLINED product costs the pool here** (measured 2026-09-03, writing
`theSameProductRepeatedIsTheSameAnswerAndADeclinedOneCostsThePoolNothing`). Two numbers,
and they point opposite ways, which is why that test's memory assertion is the only
ONE-SIDED one in the file.

- **Free memory routinely GROWS across a declined call**, by 922 MB in class order behind
  the lazy-chain tests. A call ENTERS the pool before it declines, and entering drains the
  slabs of every host array the collector has reached since the last call. A two-sided
  bound reads that drain as a leak; the first draft of the test did, and failed.
  **CUDA's twin assertion is one-sided too, and for a DIFFERENT mechanism** (measured
  2026-09-03): there it is `CudaGemm.allocate`'s give-back ladder, which on a request that
  does not fit while `residency.occupied()` collects, trims, re-asks, and then evicts every
  resident copy the call does not hold -- +284 MB across twelve declined products in class
  order, and in isolation +1.29 GB with a gigabyte resident against 0 bytes with nothing
  resident, since the ladder is gated on `occupied()`. So one-sidedness here is not a
  local accident of this backend: both backends grow free memory across a decline, by
  unrelated routes, and the two `cuMemGetInfo` assertions in `GpuTest` that ARE two-sided
  are the ones taking both endpoints straight after `releaseResident()`, where residency is
  empty and neither route has anything to give back.
- **A declined product whose operands FIT is allocated before the encode discovers it
  cannot proceed**, and the slabs go to the free lists: `freeDeviceMemory` drops by 3.2 GB
  at n = 12000, 6.4 GB at 20000 and 12.9 GB at 32768 -- three operands' worth each time.
  It is a TRANSIENT and not a leak (the pool recycles them; free memory is back at its
  baseline after the sweep, and `usable()` never goes false), and on a 107 GB working set
  it is harmless. It is written down because it is the shape of the CUDA failure that
  `GpuTest.aDeclinedProductCostsTheDeviceNothing` exists for -- there a failing pooled
  allocation took the card from 69 GB free to 1 GB and never gave it back -- and the two
  are genuinely different: this one comes back. Above that the allocation fails outright
  (10 GB an operand at n = 50000, 40 GB at 100000) and nothing is taken, which is why the
  test uses 100000: it is the shape whose cost is stably zero.

### The same two questions on CUDA (2026-09-03)

The two sections above were written on an M4 Max. This is their CUDA half, swept on an
NVIDIA GB10 (sm_121, 48 SMs, driver API 13.0, pooled allocation) with the device in force.
The thresholds every number below is relative to:

```
work 131072  map 16384  strided 32768  fold 131072  fused 131072  rng 8192  matvec 131072
foldMinCells 256   supportsDouble true   lazyResultsPay true
```

**`am/ik/gpu/GpuDeclineTest` is ungated, so on this machine it runs WITH the device**, and
whether each of its enumerations is therefore a free "with a device present" pin is decided
by its hard-coded shapes -- which are hard-coded deliberately and must stay that way, since
a GPU-less runner may not size itself off a machine's thresholds. Measured by asking the
same shape WELL-FORMED and reading the accept/decline answer:

| enumeration | its fixed shape | here |
|---|---|---|
| `everyDeclineConditionDeclinesRatherThanThrows` | 64-cube, work 262144 | accepted -- a free device-present pin |
| `theDestinationTakingFormDeclinesOnTheSameConditions` | 64-cube | accepted -- free pin |
| `everyElementWiseDeclineConditionDeclinesRatherThanThrows` | `mapMinElements() * 2` = 32768 | accepted -- free pin |
| `everyBatchedDeclineConditionDeclinesRatherThanThrows` | 8 x 64-cube, work 2097152 | accepted -- free pin. **This is the one that is vacuous on Metal**, whose floor is 4194304 |
| `everyMatrixByVectorDeclineConditionDeclinesRatherThanThrows` | 512 x 256 = 131072, EXACTLY the floor | the bounds run in `Gpu.matvec`'s `offeredMatvec` BEFORE the size and residency gate, so every condition is reached; the well-formed call declines on the first sight and is accepted on the second |
| `everyGeneratorFillDeclineConditionDeclinesRatherThanThrows` | 16384 against an 8192 floor | accepted -- free pin |
| `everyStridedDeclineConditionDeclinesRatherThanThrows` | 4096 x 64 = 262144 | accepted for the broadcast, the gather AND the fold (262144 clears both floors) -- free pin, and the ONLY pin the fold conditions have here, see below |
| `theFusedTierDeclinesRatherThanThrowsOnEveryMachine` | 8 x 16 = 128 | **vacuous with hardware**, exactly as on Metal: every member declines on SIZE. Its own comment says so, and `GpuTest` is where the conditions are asked |
| `theResidentTierAndTheLazyHooksDecline...` | 65536, fresh arrays | declines because nothing is resident, which is its claim. Over a RESIDENT operand every member of the tier -- including the index tier and the clip norm, which are not members on Metal -- is accepted here |

**Two vacuities were found, both in `GpuTest`, both established by MUTATION** (put the
condition back to always-true and watch the value assertions still pass):

1. **`everyStridedDeclineConditionStillDeclinesWithADevicePresent`'s three FOLD conditions
   pinned nothing.** Its `rows` was sized off `stridedMinElements()` (1024 x 64 = 65536,
   comfortably over the strided floor of 32768) while the fold is gated by its OWN floor of
   131072, so the unnamed op code, the one-cell fold and the zero `inner` all declined for
   their SIZE. With `Gpu#offeredFold` forced to `true` the test stayed GREEN. The control is
   the reason this was not also a coverage hole: the same mutation turns
   `GpuDeclineTest.everyStridedDeclineConditionDeclinesRatherThanThrows` RED, because its
   fixed 4096 x 64 does clear the fold floor -- the device-free suite was carrying the
   device-present pin. Fixed by sizing off `max(stridedMinElements(), foldMinElements())`,
   which now makes both tests catch the mutation.
2. **The fused tier's ROW-COUNT floor was pinned NOWHERE, on either suite.** `Gpu#offeredRows`
   requires `rows >= foldMinCells()` (256) for a fresh operand; deleting that clause left all
   139 tests of `GpuTest`, `GpuDeclineTest`, `LinalgGpuTest` and `LinalgGpuDeclineTest`
   green. `GpuTest`'s four `rows = 4, len = 64` lines cannot be what says so -- 256 elements
   is under the map floor, so they decline on size, and the comment calling them "too few
   rows" was wrong. Pinning it takes a total ABOVE the threshold laid out in too few rows
   (128 x 2048 against 256 x 1024), with the 256-row form asserted ACCEPTED first.

**The fix's shape, and it is the general one**: a `...StillDeclinesWithADevicePresent` test
now opens by asserting the same shape ACCEPTED, over the baseline's OWN arrays. Its own
arrays because an accepted call leaves its operand resident and a resident operand is offered
whatever its size -- a baseline over the enumeration's arrays would change the very gate the
declines are meant to run into. Six tests carry one now (product, element-wise, strided,
matvec -- where it takes two calls, since a GEMV is accepted on the second sight of an
unwritten matrix -- batched and fused); `everyResidentTierDeclineConditionStillDeclines...`
already had one.

**Why `aDeclinedProductCostsTheDeviceNothing`'s memory assertion is ONE-SIDED here**, which
was undocumented and is the answer to the question Metal's session asked. It is one-sided for
a real reason, and **the reason is not Metal's**. There the mechanism is the pool: a call
enters it before it declines and entering drains the slabs of every host array the collector
has reached. Here it is `CudaGemm#allocate`'s give-back ladder: when the request does not fit
and `residency.occupied()`, the pre-flight collects, trims the driver pool and re-asks, and
if that is still not enough it EVICTS every resident copy the call is not holding, drains and
trims again. So a run of declined products hands back whatever earlier tests left resident.
Measured in a real class-order run: free device memory GROWS by 284 MB across the test's
twelve declined 100000-cubes and `residentBytes()` is 0 afterwards; in an isolated probe with
1 GB deliberately made resident it grows by 1.29 GB, and with nothing resident it does not
move at all (0 bytes, because the ladder is gated on `occupied()`). **A two-sided bound would
be red on both backends, for two different mechanisms.** Keep it one-sided; the direction that
matters is the one the test was written for -- a decline that TAKES memory and does not give
it back.

**And that is why the file's OTHER two `cuMemGetInfo` assertions can be two-sided**
(`Math.abs(before - freeDeviceMemory()) < DRIFT_BOUND`, in
`theResidentSetIsBoundedByItsBudgetAndAReleaseGivesTheMemoryBack` and the lazy-results
eviction test): each takes its `before` right after a `releaseResident()` and its `after`
right after another one, so BOTH endpoints have an empty residency and the ladder has nothing
to hand back in between. `aDeclinedProductCostsTheDeviceNothing` deliberately does not release
first -- the failure it exists for is about a decline over a device in whatever state the
program left it -- so its endpoints are asymmetric by construction. The rule to carry: a
two-sided device-memory bound is available only where the residency is empty at both ends.

**What was swept, so the next reader does not sweep it again**: every enumeration in
`am/ik/gpu/GpuDeclineTest` (all 27 tests) against the thresholds in force here, and every
`...StillDeclinesWithADevicePresent` test in `am/ik/gpu/GpuTest`, plus that suite's three
`cuMemGetInfo` assertions for one-sidedness. **What was NOT swept**: the rest of `GpuTest`'s
57 tests for the vacuity hazard (`MetalGpuTest` got that treatment on 2026-09-03 and was
found clean, but the CUDA suite's non-decline tests have only been checked against the
reachability rule, not against "did the mechanism run"), and `codegen/jvm` and `eval`, which
the 2026-09-03 Metal sweep covered.

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
- **No index tier and no clip norm on METAL** -- and the correction this bullet has now
  needed twice. It first said "no lazy results on Metal for the interceptors, and so no
  index tier or clip norm there", which todo-495 made obsolete by making that backend's
  command buffers asynchronous and flipping `lazyResultsPay` to true. The replacement
  then OVER-corrected, concluding that the two tiers were therefore "reachable" and
  merely unpinned; two later sweeps read that as current. **They are not reachable, and
  the mode was never what stood in the way.** `MetalGemm.take`, `takeF`, `scatter`,
  `scatterF`, `sumSquares` and `sumSquaresF` return `false` / `null` unconditionally --
  the kernels were never written. Measured over a RESIDENT operand, which is the state in
  which the CUDA half accepts them (todo-662, 2026-09-03): `takeRows` false, `pick` false,
  `scatterRows` false, `sumSquares` null. That is now an assertion rather than a reading
  of the source, in `MetalGpuTest`'s
  `theIndexTierTheClipNormAndTheAffinePairAreNotMembersHereAndDeclineOverAResidentOperand`
  -- over a resident operand precisely because that is the only state that separates "not
  a member here" from "not resident yet", and because a round that writes the kernels has
  to come to that test to change the answer.
- **No fused layer-norm AFFINE on METAL.** It is built on CUDA ("Layer-norm's affine")
  and declined here, unmeasured, so the module runs the normalization and its two
  broadcast passes member by member as before; whether the fold pays on that backend is
  the measurement to make. The other three declines this bullet used to carry are gone
  outright, each to its own: the fused `log-softmax` is todo-629's, the attention
  scale-and-mask todo-641's on CUDA and todo-643's on Metal (a tape change, which is what
  the views in `.kb/torch.md` are), and the fused tier on Metal todo-636's -- built on the
  software binary64 the resident tier needed there.
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
