# `--gpu`: a matrix product on the GPU, or a decline

Two layers, in one file. `am.ik.gpu` is the foundation (todo-123 phase 1, landed on its
own): a language-independent library that takes a matrix product and either runs it on an
NVIDIA GPU or answers `null`. **The `--gpu` flag on the INTERPRETER** (phase 1B) is the
first interceptor over it, and "The interception layer" below is its whole record -- the
per-backend touch points, the chain order, the precision contract and the test map. **The
JVM class output** (phase 2) is the second, and "The JVM backend" below is where its one
genuinely new decision lives: what the emitted `.class` carries. A `.wasm` output still
refuses the flag outright and always will. **The STACKED product** (phase 4a, 2026-08-21)
is the second intercepted member on both of those backends, and "The stacked matrix
product" below is its whole record: the batch kernel, the threshold decision it forced,
and why it was landed BEFORE residency.

**Every number below is re-derivable.** The probes are
`.todo/123-gpu-acceleration/{AllocatorCost,CopyRoute,WorthCrossover}.java` over the shared
driver-only binding `CuLib.java`, plus `matmul-baseline-warm.lisp` for the CPU column;
that directory's README says which answers which and records what they printed. They need
the driver and nothing else -- they load the kernels this library ships rather than
compiling any -- so they run wherever the feature does.

Read `.kb/linalg-simd.md` first for the declined-input protocol this is shaped for, and
`.kb/linalg-blas.md` for the flag whose posture it copies: **recommended, never required;
a machine without the hardware runs the same programs to the same output.** Everything
below is what is DIFFERENT about a GPU, and the differences are the fixed cost of a round
trip and the fact that the accelerator is a separate machine with its own memory.

## The invariant

**`am.ik.gpu` never throws and never signals.** Every failure -- no driver, no device, an
old card, a shape it cannot launch, a product too small to be worth the trip, device
memory exhausted, any `CUresult`, a JVM that forbids native access, a platform with no
`libcuda.so.1` (so far, anything but Linux) -- is the same answer: `null`, and the caller
runs whatever it would have run anyway. That is what lets a future `--gpu` be a silent
no-op on a machine without a GPU, exactly as `--simd` is on a JDK without
`jdk.incubator.vector`.

**Two things are deliberately NOT declines.** A `null` operand array throws
`NullPointerException`: the package is `@NullMarked`, so a null there is a contract
violation rather than an input, and swallowing it would hide a caller's bug behind a silent
CPU fallback forever. And the array-returning `multiply` overloads allocate the result, so
they can throw `OutOfMemoryError` exactly as `new double[n * p]` can -- the `out`-taking
overloads, which is what an interceptor should call, allocate nothing. Both are in the
class javadoc's contract.

Package rule, per CLAUDE.md's rule for `am.ik.jvm` / `am.ik.wasm` / `am.ik.wit`:
**language-independent -- it imports no rontolisp package and no external dependency.**
Nothing outside it is needed to talk to a GPU. The direction the interceptors will take is
`eval -> am.ik.gpu` and `codegen.jvm -> am.ik.gpu`, and `am.ik.gpu -> nothing`.

| class | what it owns |
|---|---|
| `am.ik.rontolisp.eval.LinalgGpu` | the interpreter's `--gpu` interceptor: `available`, `description`, `install` |
| `am.ik.rontolisp.eval.LinalgGpuKernels` | the ONE reference to `am.ik.gpu` from `eval`, so `-Pweb` can cut it |
| `am.ik.rontolisp.codegen.jvm.JvmGpuTemplate` | the compiled call site's glue: the packed representation and the null sentinel |
| `am.ik.rontolisp.codegen.jvm.JvmGpuRuntimeBuilder` | the blob: `am.ik.gpu`'s class files + the PTX, renamed and embedded |
| `am.ik.rontolisp.codegen.jvm.JvmLinalgGpu` | which members the device bridge claims (two), and each one's `ops` key |
| `am.ik.gpu.Gpu` | the whole public surface: `available`, `description`, `worth` (a product or a stack), the `multiply` overloads |
| `am.ik.gpu.CudaGemm` | the probe, the context/module lifetime, and the per-call product |
| `am.ik.gpu.CudaDriver` | the FFM binding: `libcuda.so.1` and 24 downcall handles |
| `am.ik.gpu.CuResult` | every CUDA 13 status code, and which of them leave the context dead |
| `src/main/resources/am/ik/gpu/gemm.cu` / `gemm.ptx` | the kernels, source and checked-in artifact |

## The API

```java
static boolean available()                       // does this machine have one
static String  description()                     // what was found, or why nothing was
static boolean worth(long n, long m, long p)              // is this product big enough to offer
static boolean worth(long batch, long n, long m, long p)  // ... is this STACK of them
static void    useKernels(String ptx)            // for an embedder that has no resources
static double[] multiply(double[] a, int offsetA, double[] b, int offsetB, int n, int m, int p)
static float[]  multiply(float[]  a, int offsetA, float[]  b, int offsetB, int n, int m, int p)
static boolean  multiply(double[] a, int oA, double[] b, int oB, double[] out, int oOut, int n, int m, int p)
static boolean  multiply(double[] a, int oA, int strideA, double[] b, int oB, int strideB,
                         double[] out, int oOut, int batch, int n, int m, int p)
```

Row-major `n x m` by `m x p`, a fresh `n * p` array back or `null`; the `out`-taking forms
answer `true` / `false` and allocate nothing, and each has a `float[]` sibling. Two shapes
of the same decision are deliberate: `worth` so a caller can refuse before it unwraps its
operands, and `multiply` re-asking anyway so it cannot be bypassed.

**The batched pair is the same call plus a per-batch ELEMENT STRIDE on each operand** --
one launch for the whole stack. A stride may be 0, which is what a BROADCAST operand
passes, and then only ONE slab of that operand is copied: the span a launch reads is
`(batch - 1) * stride + n * m`, not `batch * n * m`. That is not a micro-optimization, it
is the shape every `torch:linear` over a `(B T C)` activation has.

**The offsets are mandatory, not a convenience.** The compiled backends keep a
`[rank, dim..., data...]` header inside the same array as the data, so an interceptor on
the JVM must be able to say where the elements start; the interpreter passes 0. The result
carries no header, so the caller wraps it.

## The runtime requirement is `libcuda.so.1`, and nothing else

`SymbolLookup.libraryLookup("libcuda.so.1", Arena.global())` plus a `downcallHandle` per
entry point. No JNI, no bundled shim, no Java library, and **no CUDA toolkit**: no
`libnvrtc`, no `libcudart`, no `libcublas`. `libcuda.so.1` ships with the NVIDIA driver, so
"has a working GPU" is the entire runtime requirement, and that is what makes this
compatible with the no-external-dependencies rule rather than a compromise on it.

The spike bound NVRTC to compile CUDA C at run time. This does not, and must not: NVRTC is
in the toolkit.

## The kernels: PTX checked in, JIT-compiled by the driver

`gemm.cu` is the source; `gemm.ptx` is what `nvcc` makes of it, and both are checked in
under `src/main/resources/am/ik/gpu/`. At run time `cuModuleLoadData` hands the PTX text to
the driver, which JIT-compiles it for whatever card is present. Regenerate the pair
together, from the repository root, with a CUDA toolkit installed -- a DEVELOPER
requirement only:

```bash
nvcc -arch=compute_75 -ptx src/main/resources/am/ik/gpu/gemm.cu -o /tmp/gemm.ptx
sed -n '1,12p' src/main/resources/am/ik/gpu/gemm.cu > src/main/resources/am/ik/gpu/gemm.ptx
cat /tmp/gemm.ptx >> src/main/resources/am/ik/gpu/gemm.ptx
```

The three lines are one command in spirit: `nvcc` cannot prepend a header, so the first
twelve lines of `gemm.cu` -- which are `//` comments, and therefore valid PTX -- are copied
onto the front of the generated text. That is how the regeneration command travels with
the artifact instead of only living here. `GpuDeclineTest` asserts it is still there.

- **`compute_75` (Turing, 2018) is the floor because CUDA 13 refuses to target anything
  older**, not because we chose it. A card below compute capability 7.5 declines at the
  probe, with that as its reason.
- **The load is not a startup problem and needs no cache plumbing of ours.** Measured on
  the GB10: 26 ms the first time a given PTX text is seen, **1.4 ms every run after
  that**, because the driver keeps its own on-disk cache in `~/.nv/ComputeCache` and the
  resource is a fixed text. So no `cuModuleLoadDataEx` options are passed.
- The kernel is the spike's 16x16 tiled GEMM at f32 and f64, unchanged. That is the
  answer and not a stopgap: at f64 it MATCHES cuBLAS (0.9x, both pinned by the same scarce
  fp64 units), and at f32 the 7x cuBLAS wins on kernel time collapses to 1.2-3.0x once the
  copies are on the clock. todo-123 has the full table and the reasoning; nothing here
  opens `libcublas`.
- **Four entry points since phase 4a**: `gemm_f64` / `gemm_f32` and the stacked siblings
  `gemm_batched_f64` / `gemm_batched_f32`. A batched kernel is six lines -- it offsets the
  three pointers by `blockIdx.z` times the strides and calls the SAME `gemm<T>` device
  function -- which is why a batched cell folds `k` bit-identically to an unbatched one
  and the precision contract below needed no second sentence. `gemm.cu` grew by 22 lines
  and the PTX by 354; the regeneration command is unchanged, and `nvcc` emits the batched
  entries after the plain ones. The element-wise tier is a later phase and the PTX
  regenerates again then.
- **`Gpu.useKernels(String)` supplies the text for an embedder that carries the library's
  CLASSES but not its resources**, and is read by the probe ahead of the resource. It
  exists for exactly one caller -- the JVM backend, whose emitted class renames these
  classes into its own package where a classpath resource of ours cannot follow (below).
  A call after the probe has run changes nothing and is not an error.

## The availability probe

One probe per process, cached, in `Gpu`'s static initializer, and it answers on every
machine without throwing:

1. `CudaDriver.open()` -- the library lookup. Absent driver, wrong platform, forbidden
   native access, or a driver too old to export an entry point: `null`, and the answer is
   "this machine has no NVIDIA driver".
2. `cuInit`, `cuDeviceGetCount`, `cuDeviceGet`.
3. compute capability `>= 7.5`, checked explicitly so the reason is legible rather than a
   `CUDA_ERROR_NO_BINARY_FOR_GPU` from the module load.
4. `cuDevicePrimaryCtxRetain` + `cuCtxSetCurrent`.
5. the PTX resource, `cuModuleLoadData`, and `cuModuleGetFunction` for both kernels.
6. one `cuMemAllocAsync`/`cuMemFreeAsync` pair, to find out whether this driver and card
   can serve per-call memory from the driver's pool (below).

`description()` is the outcome either way -- `NVIDIA GB10 (sm_121, 48 SMs, driver API
13.0)` on a machine with one, a sentence on a machine without -- and is what a CLI should
print when it was asked for a GPU and cannot have one.

**Only the stream-ordered allocator is an OPTIONAL symbol.** Everything else has been in
the driver API since CUDA 4, so a driver missing one of those is not a driver, and the
whole binding declines rather than half-binding.

## Lifetimes

- **Retained once, for the process.** The primary context and the module are exactly what
  a per-call intercept must not pay for. They are never released on the success path; the
  process exit releases them.
- **Every partial failure in the probe unwinds what it had acquired** (`CudaGemm.unwind`):
  a machine that declines at step 5 does not leave a retained primary context or a loaded
  module behind. This is the leak the decline path would otherwise have.
- **Per call, three device buffers, freed on every path** -- success, decline and failure
  alike, in a `finally`. Two tests pin it and they are not the same test:
  `aRunOfSuccessfulProductsFreesEveryBufferItAllocates` runs 1000 products that WORK, and
  `aDeclinedProductCostsTheDeviceNothing` runs twelve that FAIL, which is the path the
  first one never enters and the one that was wrong. Both assertions are two-sided --
  free memory that GREW would mean the test is measuring the rest of the machine. The
  first one's bound is deliberately LOOSE (256 MB against the 1.5 GB a leak costs):
  `cuMemGetInfo` is a property of the device, not of the thread, and since phase 2 the
  JVM backend's tests run in a second surefire fork where every compiled class defines
  its own copy of this binding and loads its own module. It was 64 MB over 500 products
  and that is too tight to survive a parallel fork -- measured, 159 MB of drift.
- **Nothing is cached between calls.** Phase 3 (residency) is where that changes, and it
  needs the invalidation rule todo-123 describes before it can exist.
- **Threads.** The driver API is thread-safe and every call owns its buffers, so concurrent
  products are correct without a lock; they serialize on the device anyway, because
  everything goes to the null stream. The one caveat a future interceptor should know: a
  copy issued while ANOTHER thread's kernel is still queued on the null stream waits for
  it, and waits for it INSIDE the critical window. Per-thread streams are the fix and phase
  3 is where they would land.

### Per-call allocation is the floor, and it is what the spike measured around

The biggest correction this work makes to todo-123. Every spike probe allocated its device
buffers ONCE and then looped, so its "~16-18 us floor" excluded allocation. A per-call
intercept cannot do that, and it needs three buffers a call. Measured on the GB10
(`AllocatorCost.java`):

| in isolation | | |
|---|---|---|
| `cuMemAllocAsync` + `cuMemFreeAsync`, one pair | **0.7-2.3 us** | flat in the size |
| `cuMemAlloc` + `cuMemFree`, one pair | **136-336 us** | 62-513x the pooled pair |
| `cuCtxSetCurrent` | 0.34 us | |
| `Arena.ofConfined` + allocate | 0.94 us | |
| `cuCtxSynchronize`, nothing outstanding | 0.26 us | |

| a whole f64 product, allocation included | pooled | unpooled |
|---|---|---|
| n=64 | **26 us** | 181 us |
| n=128 | **43 us** | 209 us |
| n=256 | **126 us** | 303 us |
| n=512 | **692 us** | 1203 us |

**The two tables are two measurements, not one derivation.** An isolated `cuMemAlloc` pair
costs 136 us; the same pair inside a steady product loop costs 52-59 us, because the
driver is not being asked cold. So the unpooled floor is ~180 us because that is what a
product MEASURES, not because 3 x 136 = 408. (An earlier draft of this file wrote "three
pairs are needed per product... floors at 170 us", which is a derivation that does not
close and was never how the figure was obtained.) Either way the conclusion holds and the
factor is large: the products allocate through `cuMemAllocAsync` on the null stream, fall
back to `cuMemAlloc` only where the trial in the probe failed, and the size threshold moves
with the floor when they do (see `worth` below). The pool is the DRIVER's; this library
owns no device memory once a call returns.

### A DECLINE MUST COST THE DEVICE NOTHING, and that takes three calls in order

The invariant's sharpest edge, and the one place the first version of this library broke
it outright. A pooled allocation that FAILS still grows the pool as far as it can on the
way to failing, and hands back no pointer -- so there is nothing to free, and the pool
keeps the high-water mark for the life of the process AND against every other CUDA process
on the card. Measured before it was handled: one declined 80 GB product took a 128 GB
device from 69 GB free to 1 GB free, permanently, while returning `null` exactly as
designed and letting the CPU compute the right answer.

Two guards, and the second one has an ordering trap in it (`AllocatorCost.java`'s third
block walks the whole sequence):

1. **A pre-flight.** The three buffers' total is checked against `cuMemGetInfo` less
   64 MB of headroom before anything is allocated, so a product that cannot fit never
   grows the pool at all. It costs 0.6 us on a call that is going to succeed.
2. **A trim after a failed allocation** -- for the case the pre-flight cannot see coming,
   where free memory changed underneath it. Three calls, in this order, or it silently
   does nothing:
   - `release()` the buffers that DID allocate (a trim finds them in use otherwise --
     measured, a declined product held 78 GB with the two swapped);
   - `cuCtxSynchronize`, because `cuMemFreeAsync` is STREAM-ordered and the buffers are
     only QUEUED to return to the pool (measured, a trim before the sync returns
     `CUDA_SUCCESS` having freed nothing);
   - `cuMemPoolTrimTo(pool, 0)`.

With all three, twelve consecutive declined 80 GB products move free device memory by
0 MB. `GpuTest.aDeclinedProductCostsTheDeviceNothing` is the pin, and it asserts the
memory rather than the return value, because the return value was always right.

## `Linker.Option.critical` takes heap segments here too -- with a different bound

`.kb/linalg-blas.md` established that a `critical(true)` downcall accepts a HEAP
`MemorySegment`, which removed the heap-to-native staging copy for `--blas`. todo-123's
text predates that finding and still calls the host copy "unavoidable in every row". **It
is not.** `cuMemcpyHtoD` and `cuMemcpyDtoH` take `MemorySegment.ofArray(a).asSlice(...)`
directly under `critical(true)`, and the offset parameter rides along for free.

Measured on the GB10, f64, one `n x n` product end to end, us per call through the whole
library path (device allocation included), against the same path with the operands staged
in a per-call confined arena:

| n | staged in a confined arena | critical, no host copy | |
|---|---|---|---|
| 8 | 16.5 | **15.8** | 1.04x |
| 32 | 19.3 | **17.7** | 1.09x |
| 64 | 24.2 | **21.4** | 1.13x |
| 128 | 63.0 | **41.5** | 1.52x |
| 256 | 211.6 | **126.8** | 1.67x |
| 512 | 1061 | **701** | 1.51x |
| 1024 | 15184 | **4908** | 3.09x |
| 2048 | 89202 | **38598** | 2.31x |

The gap widens rather than closes with size, unlike `--blas`'s, because the staging buffer
is a native allocation of the operand's size on every call -- a per-call `mmap` and page
fault of 8 MB at n=1024, which is most of the 3.1x. **So there is no size at which staging
wins, and the library never stages.**

The same tradeoff `--blas` documented still applies: a critical call does not transition
the thread to native, so the VM cannot reach a safepoint while it runs. **A GPU has TWO
ways for that window to get long, they need separate rules, and neither rule is "stage
it".**

1. **The copy itself is bandwidth-bound**, so a copy bigger than
   `CRITICAL_CHUNK_BYTES = 1 << 26` (64 MB) is SPLIT into chunks of that size rather than
   staged: the driver moves 64 MB, the thread becomes safepointable, the next chunk goes.
   64 MB is ~1.1 ms of copy on this machine (measured 16.9 us/MB), and an extra downcall
   per 64 MB is nothing beside it. This is where the GPU can do better than `--blas`,
   whose library call is not divisible.
2. **A device-to-host copy on the null stream also WAITS for the kernel.** This has no CPU
   analogue and it is the trap: a critical `cuMemcpyDtoH` issued straight after a launch
   holds the thread off a safepoint for the kernel's whole runtime -- measured 36 ms at
   n=2048 f64, 283 ms at n=4096, against 548 us and 2.2 ms for the same copy issued after
   an explicit wait. Chunking cannot help, because the wait lands on the first chunk. So
   the kernel is awaited by a plain, thread-transitioning `cuCtxSynchronize` before the
   result comes back, whenever the launch is big enough for that to matter.

   **The threshold is per-device, because a flop count is not a duration.**
   `SYNC_FLOPS_PER_MULTIPROCESSOR = 1 << 22`, multiplied by
   `CU_DEVICE_ATTRIBUTE_MULTIPROCESSOR_COUNT` (which the probe was already reading for the
   description string). On the 48-SM machine this was calibrated on that is 2^28 flops,
   which is the ~0.6 ms the budget is meant to be; on a device with a quarter of the SMs
   the same duration is a quarter of the flops, and a fixed count would have put several
   unsafepointable milliseconds inside a critical copy there. The comparison is `>=`, not
   `>`: n=m=p=512 at f64 lands exactly ON 2^28 and has to be on the syncing side of it.
   The width needs no second factor -- the calibration device already runs fp64 at 1/44 of
   its fp32 rate, which is the bad case.

Below both ceilings a product is three critical copies, a launch, and nothing else. The
plain (non-critical) copy handles do not exist: there is no route that wants them.

## Declining on error, and the sticky rule

`CuResult` is the full CUDA 13 table -- **101 statuses, diffed against `cuda.h` at
`CUDA_VERSION 13000`** -- with one property this library reasons about: `sticky()`. The
human sentence is not duplicated; the driver supplies it through `cuGetErrorString`, and
`CudaDriver.errorString` asks.

**Re-diff the table against the header when it is extended.** An invented constant is
worse than a missing one, and not merely as bookkeeping: an unknown code is treated as
sticky and retires the feature, so a constant that does not exist but IS in the table --
and therefore has a `sticky` flag someone guessed -- can leave a dead context paying full
round trips to fail. The first draft of this table carried two such (`917`, `918`, both
marked non-sticky, in no CUDA 13 header); they are gone.

- **Any non-zero status declines**, after freeing every buffer. `CUDA_ERROR_OUT_OF_MEMORY`
  is an ordinary decline: this product was too big, the next may fit.
- **A sticky status retires the feature for the rest of the process.** The seventeen
  marked statuses (the launch failures, the uncorrectable memory errors, a destroyed or
  deinitialized context, a driver mismatch) leave the context unusable, so every later
  call would pay a full round trip to fail. `CudaGemm` sets `usable = false` and `Gpu`
  answers "unavailable" from then on without touching the driver again.
- **An unrecognised code is not an error condition of its own** -- a newer driver may
  return a status this table predates. `CuResult.of` answers `null`, `describe` still
  produces a string, and `isSticky` assumes the dangerous kind.

## `worth(n, m, p)`: measured against the fastest CPU path, not against FLOPs

`n * m * p >= 1 << 17`, about a 51x51x51 product.

The floor is flat, so what the device has to beat at small n is not CPU arithmetic but
rontolisp's own per-call cost, and that is cheap. GB10, us per one `n x n` product,
`--simd` on the JVM (`matmul-baseline.lisp` with more sizes, 200 warm-up iterations and
4000 reps) against this library:

| n | `--simd` f64 | gpu f64 | `--simd` f32 | gpu f32 |
|---|---|---|---|---|
| 32 | **9.5** | 17.4 | **6.3** | 15.5 |
| 48 | 23.0 | **19.2** | **14.0** | 16.5 |
| 56 | 34.5 | **~20** | 20.3 | **~17** |
| 64 | 49.0 | **21.6** | 28.5 | **17.3** |
| 96 | 131 | **31.6** | 73.5 | **18.5** |
| 128 | 384 | **41.7** | 191 | **22.4** |
| 256 | 2785 | **128.7** | 1465 | **49.1** |
| 512 | 20975 | **701.8** | 10635 | **185.8** |
| 1024 | -- | 4914 | -- | 1095 |
| 2048 | -- | 38703 | -- | 8195 |

The crossover is between n=32 and n=64 at both widths -- n≈45 at f64, n≈51 at f32,
where the two are level (23.0 against 19.2, and 14.0 against 16.5, at n=48). 2^17 is a
50.8x50.8x50.8 product, so the threshold sits exactly on the later of the two: declining
costs nothing, and a "win" that is really a tie is the one way this can do harm.

**Two things todo-123's table gets wrong, and both matter for this number.** Its `--simd`
column is not JIT-warm at the small end (3 warm-up iterations, 20 reps): it quotes 100 us
at n=32 and 450 us at n=64 where a warm run (200 warm-ups, 4000 reps, on an otherwise idle
machine) costs 9.5 and 49, so it makes the crossover look about 10x lower than it is. And its GPU column excludes device allocation, as above. The
two errors point in opposite directions and roughly cancel at n=64, which is why "the
crossover is around n=64" survived; the individual figures did not.

`--blas` puts the same predicate at 64 rather than 131072 because a critical downcall into
a CPU library floors at 30 ns and a GPU round trip at 15 us. Three orders of magnitude of
fixed cost, three orders of magnitude of threshold.

When the probe could not use the stream-ordered allocator the floor is ~180 us and the
threshold is `1 << 21` instead, which is the crossover against the same CPU column between
n=96 (131 us) and n=128 (384 us).

**`Gpu.worth` applies the POOLED threshold on every machine, and `Gpu.multiply` applies the
one actually in force.** That is deliberate and it is what keeps `worth` honest as the
cheap pre-check it is documented to be: knowing which threshold this machine uses requires
the probe -- a `dlopen`, a `cuInit`, a retained primary context and a PTX JIT -- and an
interceptor asks `worth` on a path that may then never touch the device at all. So the
probe sits behind a holder class that `worth` does not touch, `worth` means "big enough to
be worth unwrapping the operands for", and `multiply` answers the real question. A test
pins that 100k `worth` calls stay under 200 ms on a machine with no probe run.

## Precision

An accelerated product is CLOSE to a scalar row-by-column product, not equal to it. Over
inputs that are exact at the operand width -- small integers, powers of two -- the results
still match EXACTLY, which is what `GpuTest` asserts; over inexact ones they differ.

**The mechanism is FUSED multiply-add, not a reordered reduction, and todo-123 has this
wrong.** That file (and an earlier draft of this one) says "the tile walk reorders the
reduction". It does not: `gemm.cu` keeps ONE accumulator per output cell and walks `k`
ascending across tiles and within each tile, which is exactly the scalar defun's order.
What differs is that `acc += As[ty][k] * Bs[k][tx]` compiles to `fma.rn.f64` /
`fma.rn.f32` -- 16 of each per tile in the checked-in PTX -- so every term is rounded ONCE
where the defun rounds twice. That is why the f64 divergence is a few ulps rather than the
`sqrt(n)`-ish growth a reordering would give, and it is why the figure to quote is an ulp
count and not todo-123's "max abs difference 1.5 to 2.7" (which was measured on operands
of a completely different magnitude and is not a scale-free number).

Measured over random zero-mean inputs (dyadic test data round-trips exactly and hides the
whole question), as a fraction of the largest cell of the f64 oracle:

| n | gpu f64 | gpu f32 | the same product accumulated in f32 on the CPU |
|---|---|---|---|
| 64 | 3.5e-16 | 2.1e-7 | 2.4e-7 |
| 128 | 3.4e-16 | 3.5e-7 | 3.8e-7 |
| 256 | 5.6e-16 | 7.8e-7 | 6.9e-7 |
| 512 | 5.0e-16 | 9.0e-7 | 9.0e-7 |

Read the last two columns together: **at f32 the divergence is the WIDTH, not the GPU** --
a CPU f32 accumulation of the same product lands at the same distance from the f64 oracle,
which is the reading the Metal spike reached on completely different hardware.
`.kb/linalg-simd.md`'s single-precision reduction contract already covers that case. At f64
the tiled kernel lands within 6e-16 relative, which is close but is still a break with the
bit-identity `#d` has under `--simd` -- and that is a decision the interceptor must make
when it lands, not this library.

## The interception layer: `--gpu` on the interpreter

The flag over the same `linalg:` seam `--simd` opened and `--blas` widened. Read
`.kb/linalg-simd.md` for the declined-input protocol (the null sentinel, the captured
binding, `LispEvaluator.applyGlobal`) and `.kb/linalg-blas.md` for the flag whose shape
this copies verbatim -- **only what is DIFFERENT about a GPU is written here.**

| backend | interceptor | kernels |
|---|---|---|
| interpreter (`prog.lisp --gpu`, native binary included) | `eval/LinalgGpu` (re-`defineFunction`) | `eval/LinalgGpuKernels` -> `am.ik.gpu` |
| JVM (`-o Prog.class --gpu`) | `codegen/jvm/JvmLinalgKernelCompiler` (call site) | `JvmGpuTemplate` -> the EMBEDDED `am.ik.gpu` |
| wasm-GC / `--no-gc` (`-o prog.wasm --gpu`) | out of scope, no FFM -- a hard error | -- |

**A `.wasm` output REFUSES rather than ignores** (`RontoLispCli.compileRecorded`, beside
the `--blas` guard, and the reason it gives is its own): silently running unaccelerated is
exactly what an acceleration flag exists to make visible. The `.class` arm of that guard
was phase 1's placeholder and phase 2 deleted it.

The user-facing description lives in `doc/{en,ja}/guides/simd-acceleration.md`
("Accelerating the matrix product on a GPU (`--gpu`)"). Keep the intercepted set, the size
threshold, the chain order and the precision contract in sync with it.

### The intercepted set is TWO shapes, and it is not `--blas`'s

`linalg:dot` over two packed rank-2 operands of the same width (hence `linalg:matmul` at
rank 2 and `linalg:solve` transitively), and since phase 4a `linalg::%la-matmul-nd`, the
STACKED product behind `linalg:matmul` at rank >= 3 -- nothing else is `defineFunction`ed
and `#'linalg:add` still prints `#<lambda>` under the flag.

The set is narrower than `--blas`'s in one direction and wider in the other, and both
differences are measurements rather than staging:

- **The gemv shapes** (M.v and v.M) are NOT here, though `--blas` takes them. A
  matrix-by-vector product is memory-bound, so its whole cost is one pass over an operand
  the device would have to be handed anyway. A library call on the same core cannot lose
  that race; a round trip can.
- **The stacked product** IS here, though `--blas` does not take it (`.kb/linalg-blas.md`
  says why it stopped at `dot`: a second member wants its own hand-mirrored copy of the
  dims/broadcast/odometer helpers inside a flat template). On a device a batch axis is
  `blockIdx.z`, so it is the same kernel with two more parameters -- and it is a
  transformer's whole hot path.

The size threshold is `Gpu.worth`'s and nothing else: below `n*m*p = 2^17` -- for a stack,
below `batch*n*m*p = 2^17` -- the kernel returns the null sentinel and the CPU path runs,
which is why every example in the repository is byte-identical with the flag on.

### The stacked matrix product (`%la-matmul-nd`, phase 4a, 2026-08-21)

**Why it went before phase 3 (residency).** Phase 3's own measurement in todo-123 is a
five-op chain (`matmul`, `add`, `tanh`, `matmul`, `add`) of which only the two products
were intercepted members, so residency had almost nothing to hold on to; while this member
is compute-bound and pays with no residency at all. It is also the shape that matters
most: before it, `--gpu` declined EVERY rank >= 3 product, so a transformer gained nothing
from the flag -- which is the workload the flag exists for. Landing it first also gives
phase 3 a real chain to measure, and the measurement below says what that chain now is.

**The kernel is the unbatched kernel.** `gemm_batched_f64` / `gemm_batched_f32` offset the
three pointers by `blockIdx.z` times a per-operand stride and call the same `gemm<T>`
device function, so a batched cell folds `k` bit-identically to an unbatched one. The
precision contract is therefore "identical to a per-batch device `linalg:dot`", stated
exactly as `--simd`'s is (`.kb/linalg-simd.md`), and
`GpuTest.aBatchIsBitIdenticalToTheSameSlabsRunOneAtATime` asserts it over INEXACT operands
rather than assuming it. `batch == 1` still launches the PLAIN kernel with the parameter
block it always had, so the rank-2 path is byte for byte what phase 1 measured.

**One stride per operand, not an offset table -- and the decline that buys.** The CPU
kernel walks the batch axes as the `%la-batch-strides` mixed-radix odometer; the device
adds `blockIdx.z * stride`. The two agree exactly when every axis's stride is that one
stride times the axis's own weight in the counter, which is true for a contiguous batch of
any rank and for a wholly broadcast operand (stride 0), and false for a broadcast axis
sitting UNDER a non-broadcast one -- `(2 1 40 40) x (2 3 40 40)` is the shape, whose `a`
offsets go `0,0,0,base,base,base`. The interceptors derive the stride in O(rank) and
answer -1 when no single stride reproduces the odometer, and -1 is a decline like any
other. The alternative was a per-batch offset table in a fourth device buffer: fully
general, one more allocation and copy per call, and it would have made the common case pay
for a shape no example has. **A broadcast LEADING axis is stride 0 and needs no special
case at all**, exactly as on the CPU -- and it is better than free: only one slab of that
operand is copied to the device, which is what `(B T C) x (C, out)` -- every
`torch:linear` -- does.

**The threshold is the TOTAL work, `batch*n*m*p >= 2^17`, and it is the same constant.**
This was the open design question: `worth` was calibrated for one product, and a batch of
B small products is B times the work over one round trip. It could have needed a
per-matrix floor as well -- a batch of tiny matrices moves `3*batch*n^2` bytes for
`batch*n^3` flops, so the arithmetic intensity is the MATRIX's, not the stack's. Measured
on the GB10 (interpreter, us/call, best of three rounds after a throwaway warm-up bench,
`.todo/123-gpu-acceleration/matmul-nd-baseline.lisp`), it does not:

| batch x n | `--simd` f64 | `--gpu` f64 | `--simd` f32 | `--gpu` f32 |
|---|---|---|---|---|
| 256 x 8 (2^17 exactly) | 60 | **48** | 46 | **30** |
| 64 x 16 | 75 | **43** | 71 | **29** |
| 32 x 24 | 106 | **49** | 66 | **35** |
| 16 x 32 | 110 | **45** | 69 | **29** |
| 4 x 64 | 176 | **49** | 101 | **31** |
| 16 x 64 | 710 | **86** | 400 | **56** |
| 32 x 64 | 1420 | **130** | 790 | **70** |
| 4 x 128 | 1370 | **100** | 760 | **55** |
| 16 x 128 | 5580 | **300** | 3040 | **130** |
| 12 x 256 | 31740 | **1240** | 16660 | **380** |

The device is ahead at every shape at or above the threshold, INCLUDING `256 x 8` and
`64 x 16`, where each matrix is one 16x16 tile or less and the kernel wastes half its
threads. Right ON the threshold it is a wash to 1.5x (48 against 58; at f32 `4 x 32` --
also 2^17 -- measured 22.5 against 18, the one place the CPU wins), and from 2x the
threshold up it is 2-43x. So the crossover for a stack is the same total-work point as for
a single product, the floor really is paid once for the whole stack, and no second
constant is needed. The batch is where this flag's ratio comes from: the CPU pays for
every matrix in the stack, the round trip is paid once, so the ratio grows with the BATCH
as much as with the matrix.

**Two warm-up traps, and the second one is new.** `.kb`'s existing warning (the GB10 drops
to its idle clock between small calls, so one timed round over-reports several-fold) is
not the only one: the FIRST shape measured in a process pays ~500 us/call for its first
few thousand device calls -- 533 us at `16 x 32` f64 against 44 for the identical bench
immediately after it -- and it survives best-of-three, because all three rounds are inside
the warm-up. It is the device call path being JIT-compiled (the following f32 bench is
already fast, so it is shared machinery and not the shape), and it is why the benchmark
file runs a throwaway bench at each width before anything it quotes. Every anomaly in the
first draft of the table above was this and nothing else.

**What it does to a transformer.** `train-gpt-soseki.lisp` at the CURRENT example shapes
(`*n-embd*` 8, `*block-size*` 8) is byte-identical with the flag and 0.1 s slower for the
probe: every stack in it is a few thousand multiply-adds and declines, which is the
intended answer. At the notebook's own shapes -- `*n-embd*` 384, `*block-size*` 256, the
one-line change the file documents -- on the JVM class output, `--simd` against
`--gpu --simd`:

| | 5 steps | 20 steps | per training step |
|---|---|---|---|
| `--simd` | 10.85 s | 22.72 s | 0.79 s |
| `--gpu --simd` | 8.51 s | 14.93 s | **0.43 s** |

**1.85x per step, not 26x, and that is the finding.** The per-step slope isolates training
from setup and sampling. Back out Amdahl against the microbenchmark's ~26x at these shapes
and the stacked product was about HALF the step; it is now a few percent of it, and what
is left -- the element-wise tier, `softmax`, `layer-norm`, the exact `gelu` and the AdamW
update -- is all still on the CPU. That is phase 4b's case, made by measurement rather
than by assumption, and it is the real chain phase 3 asked for.

**On the INTERPRETER the same program does not move at all** -- 5m53.6 under `--simd`
against 5m56.0 under `--gpu --simd`, five steps, same shapes -- and the reason is worth
more than the number. The interpreter leg is 88x the compiled one per step (70 s against
0.79), and it is not the matmul: measured at the notebook's activation shape, on the
interpreter,

| one call, interpreter, `#f` | `--simd` | `--gpu --simd` |
|---|---|---|
| `linalg:erf` over 1.5 M elements (the exact `gelu`) | 21.14 s | 21.17 s |
| `linalg:tanh` over the same | 0.022 s | 0.022 s |
| `(4 256 384) x (384 1536)` matmul | 0.154 s | **0.007 s** |

**And the stacked member inherits the native-image per-call cost, with the same
workaround.** One `12 x (256 x 256)` f32 stack, `--gpu --simd` against `--simd`: the
NATIVE BINARY interpreting it goes 31.0 -> 25.6 ms (1.2x), where `java -jar` goes
16.7 -> 0.38. The class the native binary EMITS runs the same stack at 1.0 ms. So the open
item from phase 2 is unchanged in shape and unchanged in remedy -- compile the program --
and phase 3 still must not quote a residency figure from the native interpreter.

`linalg:erf` is `(linalg:emap #'%la-erf-1 a)` and `emap` is never intercepted (todo-468),
so on the interpreter it is a full `eval` per element: ONE gelu costs 100x the entire
matmul budget of a step. The device made the matmul 22x faster and the program did not
notice. **The lesson for phase 4b and for todo-468: an acceleration only moves a program
if the program is spending its time on the member being accelerated, and on the
interpreter today it is not.**

### The chain order, and why the device goes on top

On the interpreter a chain is INSTALL ORDER -- each `install` captures whatever
`linalg:dot` was bound to and declines back to it -- so where `LinalgGpu.install` sits in
`LispEvaluator.resolveFunction`'s lazy-load hook IS the decision. It goes LAST, after
`LinalgSimd` and `LinalgBlas`:

```
--gpu --blas --simd  ->  device -> library gemm -> lane kernel -> scalar linalg.lisp defun
```

and every prefix of that works the same way. Three reasons, in the order they bind:

1. **`worth()` is probe-free and three orders of magnitude above `--blas`'s** (`2^17`
   against 64). The device turns down everything small before anything touches the driver,
   so being on top costs a declined call nothing. Underneath `--blas` it would never SEE a
   product: the library accepts from 4x4x4 up.
2. **Where it accepts it is at worst level with a threaded CPU BLAS and clearly ahead at
   f32.** Measured below.
3. **A declined product then lands on the best CPU path the invocation asked for**, never
   back on the scalar defun -- which is the composition rule todo-123 asks for, stated for
   three layers instead of two. Pinned by
   `whatTheDeviceDeclinesFallsOnTheBestCpuPathEnabledAndNotOnTheDefun`, which uses
   `.kb/linalg-simd.md`'s own f32 v.M probe: the fallback target is legible because the
   defun prints 16778240 and the lane kernel 16777216.

**For the STACKED member the chain has no library rung**, because `--blas` does not
intercept it: `--gpu --blas --simd` is device -> lane kernel -> scalar defun there, and
`--gpu --blas` alone is device -> defun. Pinned at rank 3 with the same f32 probe
(`aDeclinedStackFallsOnTheLaneKernelWhenSimdIsOnAndOnTheDefunOtherwise`, in both the
interpreter and the JVM suite), because "the flags compose" is a claim per MEMBER and not
per flag.

**The wart, measured and accepted:** at n=64-96 with `--gpu --blas` both on, the device
accepts a product a 20-core OpenBLAS would have finished sooner (139 us against 21 at
n=64, f64). `worth()` is calibrated against `--simd`, which is what a machine without a
tuned library has, and it cannot be calibrated against `--blas` without `am.ik.gpu`
learning whether a CBLAS is loaded -- which would make a language-independent library
depend on one. The band is narrow, both sides are far under a millisecond, and the
alternative (asking the library first) gives away the 2-5x at the sizes the flag exists
for. Revisit if phase 3 changes the floor.

**And the same wart is much wider on the NATIVE BINARY, for a reason that is not the
interceptor's.** Measured on the same machine, `-Pnative` binary against `java -jar`, one
n=512 f64 product: `--gpu` 18500 us against the JVM's 735, and `--blas` 7800 against 1160.
Both flags lose several-fold in the native image -- the BLAS half is explicable
(single-threaded there), the GPU half is 25-60x with no threading involved, so the FFM
downcall path in the image is the suspect and neither `am.ik.gpu` nor `eval/LinalgGpu`
changes between the two. The consequence for the chain: on the native binary today
`--gpu --blas` is SLOWER than `--blas` alone at every size measured, where on the JVM it is
faster from n=256 up. `--gpu` still beats `--simd` there (18.5 ms against 41.5) and the
scalar defun by four orders of magnitude (132 SECONDS at n=512), so the flag is worth
having in the binary -- but the per-call native-image cost is an open item and it is the
first thing to measure before phase 3 quotes any residency figure.

### The JVM backend: the whole library travels in the class

The decision phase 2 existed to make, and it went the OTHER way from `--blas`'s.

`--blas` embeds one flat template class (`JvmBlasTemplate`, 375 lines) that is a hand-kept
COPY of `eval/LinalgBlasKernels` -- `.kb/linalg-blas.md` has to say "MIRRORED... change
them together" about the candidate list, the marker rule and two constants.
`.kb/template-class-embedding.md` says a template may carry no second class file, so the
default assumption was a flattened copy of `am.ik.gpu` too. **It is not what landed.** A
GPU binding is ~1700 lines across four classes (six class files) plus a PTX resource, and
the parts a copy would fork are exactly the parts phase 1 spent its time on: the decline
that must cost the device nothing (three calls, in one order), the 101-entry `CUresult`
table and which seventeen statuses are sticky, the per-device safepoint threshold, the
chunked critical copies. Two hand-synced copies of THAT is a standing bug.

So `JvmGpuRuntimeBuilder` generalizes the template mechanism from one class to a CLOSURE
of them plus one data resource:

- every class file of `am.ik.gpu` is read from the compiler's classpath and renamed by ONE
  prefix rule, `am/ik/gpu/` -> `RontoLispGpu`, so `Gpu` becomes `RontoLispGpuGpu` and a
  nested `Gpu$Probe` follows its outer class without being named;
- `JvmGpuTemplate` -- the call site's glue, ~130 lines: the packed `[rank, dim..., data]`
  header, the null sentinel, nothing else -- is renamed to `RontoLispGpuBridge` by the
  same pass, which is what lets it be WRITTEN against `am.ik.gpu` and type-checked by
  javac while resolving to the embedded copies at run time;
- each is base64'd into its own chunked string constant, and the emitted `_gpuInit` runs
  one `MethodHandles.lookup().defineClass` per blob. Definition order is free: a class
  file's references to its siblings resolve lazily, on the first instruction that uses
  one, long after all of them exist.

**The size objection does not survive measurement.** The six class files are 47.4 KB and
the PTX 10.4 KB; base64 comes to ~78 KB of constant pool, against the 62 KB
`JvmSimdVectorTemplate` (83 KB base64) that every `linalg` program under `--simd` already
embeds. It is the same order as a mechanism this project uses routinely -- so the blob
bought the elimination of a 1700-line fork for no more than the bridge beside it costs.

Two routes were weighed and rejected, and the reasons are worth keeping:

- **A `--gpu`-only support jar on the classpath.** Cheapest blob of all, and it makes
  `-o Prog.class --gpu` non-standalone -- a real departure, since every other flag emits a
  class that runs with a bare `java Prog`. Rejected on that alone.
- **A thin template that reaches `am.ik.gpu` REFLECTIVELY when the rontolisp jar happens
  to be on the classpath, and declines otherwise.** Also cheap, and it is a SILENT
  degradation of a kind this feature does not otherwise have. "No device" declining
  quietly is a property of the MACHINE, which `--gpu` on the interpreter reports on
  stderr; "you forgot a jar" is a property of the INVOCATION, and an acceleration flag
  exists to make exactly that visible. Not acceptable here.

**The kernels cannot be a resource on the other side.** `CudaGemm` reads `gemm.ptx` from
beside itself; renamed into a compiled program's default package there is no such resource
and there never can be, so the PTX rides in the same blob as an ordinary string constant
(verbatim, not base64 -- it is ASCII text) and `_gpuInit` hands it to `Gpu.useKernels`
before anything can probe. That one public method is the entire cost this route imposed on
the language-independent library, and it is a legitimate embedder API rather than a
rontolisp hook.

**What it is NOT.** The renamed classes are defined into the emitted class's own loader,
so two `--gpu` classes loaded by ONE classloader would collide on `defineClass` -- the
same property `--simd` and `--blas` bridges already have, and the reason the compiled-
backend tests give each program a fresh `URLClassLoader`. Each such loader also probes and
JIT-loads the module again (~1.4 ms warm, and its own device memory); a real program has
one.

#### The call site: one chain over one set of temps

`JvmLinalgKernelCompiler.compile` -- the one `linalg:` call-site compiler -- now emits up
to THREE attempts, and the order it chains them in IS the interpreter's install order:

```
_gpuInit(); _blasInit(); _simdInit();          // the bridges, before their methodrefs
a = <arg1>; b = <arg2>;                        // each argument form evaluated ONCE
r = RontoLispGpuBridge.gpuDot(a, b);   if (r != null) goto end;   // --gpu (or gpuMatmulNd)
r = RontoLispBlasBridge.blasDot(a, b); if (r != null) goto end;   // --blas
r = RontoLispSimdBridge.laDot(a, b);   if (r != null) goto end;   // --simd
r = linalg$colondot(a, b);                                        // the scalar defun
```

Every prefix works the same way, and a declined product lands on the best CPU path the
invocation enabled rather than back on the defun. The temps are what make a chain of any
length safe: every decline branch RE-READS them, and recompiling the argument forms would
repeat their side effects
(`anArgumentFormIsEvaluatedExactlyOnceEvenWhenTheKernelDeclines`, now pinned in the
`--gpu` suite as well as the `--blas` and `--simd` ones).

**The device attempt is per MEMBER, not one hardcoded method.** `JvmLinalgGpu.handles`
answers for `dot` and `%la-matmul-nd`, `JvmLinalgGpu.kernelKey` maps each to its `ops` key
(`gpuDot` / `gpuMatmulNd`), and the chain above is emitted at whichever call site the
program reaches -- with the `--blas` rung simply absent at the stacked one, since
`JvmLinalgBlas.handles` still answers for `dot` alone.

The emit gate is `programUsesSymbol` over BOTH members (`JvmLinalgGpu.QUALIFIED_DOT`,
`JvmLinalgGpu.QUALIFIED_MATMUL_ND`), so it is no longer `--blas`'s gate: a transformer
reaches only the stacked member, and a gate on `dot` alone would embed no bridge for
exactly the program this flag is for. Neither flag embeds on a program that never reaches
a product. `--gpu` must NOT drag in the `--simd` bridge: a class that did would need
`java --add-modules jdk.incubator.vector` to run
(`theThreeFlagsAreOrthogonalAndEmbedTheirOwnBridges`).

The extended (option-form) call sites are `--simd`-only, as before: `dot` has no keyword
form, so the device and library attempts are simply not emitted there.

### The precision contract

`--gpu` **stays out of `ci-spec.yaml`** and the scalar `linalg.lisp` defun remains the
cross-backend oracle, exactly as for `--blas`. What is new is the size of the break and
its cause: the device fuses (above), so at `#d` -- where `--simd` is bit-identical to the
defun -- `--gpu` is not. Over inputs exact at the operand width the results still match
EXACTLY, which is what the exact-input tests assert; over inexact ones they do not, and
the pin is a RELATIVE tolerance.

Measured through the interpreter on the GB10, `--gpu` against the scalar defun AT THE SAME
WIDTH, over `sin`/`cos` of an index ramp (zero-mean and inexact; dyadic data hides the
question), worst absolute difference over the whole `n x n` result:

| n | `#d` | `#f` | ... as a fraction of the largest cell of the oracle, `#d` / `#f` |
|---|---|---|---|
| 64 | 4.0e-15 | 2.6e-6 | 8.3e-16 / 5.4e-7 |
| 128 | 2.2e-15 | 8.0e-7 | 3.8e-15 / 1.4e-6 |
| 256 | 4.9e-15 | 2.0e-6 | 4.5e-15 / 1.8e-6 |
| 512 | 4.5e-15 | 1.4e-6 | 9.1e-14 / 2.8e-5 |

The absolute column is flat and the normalized one is not, because at n=512 the largest
cell of THIS product has itself cancelled to 0.049 while the operands are O(1). Normalize
by the largest cell (as the library's own table above does) and never per cell: the worst
cell is always one whose true value cancelled to near zero, the same caveat
`.kb/linalg-simd.md` records for the `--simd` f32 product.

**A tuned BLAS fuses too, and that has a testing consequence.** On this machine OpenBLAS
and the device agree BIT FOR BIT up to n=128 (0 of 16384 cells differ at f64) and only
separate from n=192, where the library starts blocking its `k` loop. So an order pin
written at n=64 would be a tautology; `theDeviceIsAskedAheadOfATunedBlas` uses n=192 and
says why.

### `-Pweb`

`LinalgGpu.available` / `description` / `install` are the only entry points into
`LinalgGpuKernels`, which holds the only reference to `am.ik.gpu` from the `eval` half.
(`codegen.jvm` has one too since phase 2 -- `JvmGpuTemplate` -- but the web build compiles
no `codegen.jvm` template: those classes are read as RESOURCES, never linked.) `src/web/java/.../Target_LinalgGpu.java` substitutes those three, exactly as
`Target_LinalgBlas` does, and the whole CUDA binding drops out of the browser Web Image.
**A new public method on `LinalgGpu` that touches the kernels would break it, and only the
Pages workflow's Web Image build would notice** ([[web-playground-native-image-gotcha]]).
`./mvnw -Pweb compile` is the local check.

### The CLI

`--gpu` is value-less (`CliOptions.noValueKeys`) and `RontoLispCli.enableGpu` is
`enableBlas` one layer up: `LinalgGpu.available()` or a `warn` carrying
`LinalgGpu.description()`, and nothing else. **Nothing may ask `LinalgGpu.available()` on a
path that did not pass the flag** -- it runs the probe, which is a `dlopen`, a `cuInit`, a
retained primary context and a PTX JIT (~26 ms cold). That is the one way this flag is not
like `--blas`, whose availability check is nearly free.

### Tests: the interceptor

| what | where |
|---|---|
| interpreter, needs a device (`@EnabledIf` on the probe) | `eval/LinalgGpuTest` |
| interpreter, must hold on EVERY machine | `eval/LinalgGpuDeclineTest` |
| JVM: the emit gate, the blob's class list, the declined product -- on EVERY machine | `codegen/jvm/JvmLinalgGpuAccelCompilerTest` |
| JVM: the accepted product, the declines, evaluate-once, the chain, the order against `--blas` -- needs a device | the same file, `@EnabledIf` |
| the flag is value-less, the REPL pair, the `.wasm` refusal, the `.class` blob | `cli/CliOptionsTest`, `cli/RontoLispCliTest` |

The dead-flag guard is the load-bearing one, as it is for `--blas`: every numeric assertion
in `LinalgGpuTest` would pass just as well on the scalar defun, so `#'linalg:dot` printing
`#<function LINALG:DOT>` under the flag and `#<lambda>` without it is the assertion that
fails when the flag is DEAD. Since phase 4a it is TWO assertions -- `#'linalg::%la-matmul-nd`
has its own, with the double colon its qualified spelling carries -- and the compiled
half's gate assertion has a third case, a program whose ONLY linalg call is the stacked
member.

`LinalgGpuTest` also pins the two order claims above, the fallback target, and the eight
combinations of the three flags over one exact program (which now includes two rank-3
legs). The stacked member adds, in both suites: every batch shape the odometer can hand
the device (plain rank 3, a broadcast right operand, a broadcast left one, rank 4, a
rectangular non-tile-multiple slab, both widths), the three declines that are its own (a
rank-1 operand, a non-affine batch, a stack under the threshold), and the chain pin that a
declined stack lands on the LANE kernel rather than the defun. In `am.ik.gpu` the load-
bearing new one is `aBatchIsBitIdenticalToTheSameSlabsRunOneAtATime`: it states the
precision contract as an assertion instead of trusting that the batched kernel calls the
same device function. `LinalgGpuDeclineTest` is the half
a CI runner runs, and it pins that the flag changes nothing observable -- at a shape above
the threshold as well as below it.

`JvmLinalgGpuAccelCompilerTest` mirrors that split for the compiled backend, and its
dead-flag guard is the bridge NAME in the class bytes (the renamed library classes are
base64 in the blob and do not appear as text; the PTX does, which is what pins that the
kernels travel). Its unconditional half also pins `JvmGpuRuntimeBuilder.embeddedGpuClasses()`
against the class files the build actually produced -- **the guard that a class added to
`am.ik.gpu` is added to the list that travels**, since nothing can enumerate a package
from a classpath, let alone from inside a native image. Two of its device-half cases are
worth knowing about before editing them: exact-input operands must be exact IN THE FOLD
too (a 64-long sum of products of 1..4096 is not, at f32 -- the defun accumulates in f64
and no f32 kernel can follow, which is `.kb/linalg-simd.md`'s reduction contract and not
this seam), and the order pin against `--blas` uses n=192 for the reason below.

### What it is worth

Interpreter, one `n x n` `linalg:matmul`, us per call, warm (`matmul-baseline-warm.lisp`'s
bench at larger sizes; 200-warm-up rounds, best of 2-3 rounds). Same GB10, 20 Grace cores,
OpenBLAS 0.3.26 for the `--blas` column:

| n | `--simd` f64 | `--blas` f64 | `--gpu` f64 | `--simd` f32 | `--blas` f32 | `--gpu` f32 |
|---|---|---|---|---|---|---|
| 64 | 46 | **21** | 139 | 27 | **11** | 42 |
| 128 | 359 | **42** | 53 | 195 | **26** | 36 |
| 256 | 2647 | 164 | **156** | 1453 | 85 | **71** |
| 512 | 20267 | 1160 | **735** | 10567 | 510 | **215** |
| 1024 | -- | 6450 | **5150** | -- | 3083 | **1183** |
| 2048 | -- | 89200 | **38000** | -- | 44600 | **8067** |

Against the lane kernel: 7x at n=128, 28x at n=512, and 49x at n=512 in single float.
Against twenty cores of tuned BLAS: a wash to n=256, then 1.6-2.3x at f64 and 2.4-5.5x at
f32 -- which restates todo-123's own conclusion that the f64 half of `--gpu` has a credible
CPU competitor and the f32 half does not.

**These are MEANS over a rep loop; `WorthCrossover.java`'s figures above are BESTS
(`CuLib.best`).** The library at n=64 f64 is 21 us at its best and ~60 us on average
through the same route, because per-call pool allocation and the driver's own jitter are
real. Do not compare a row of this table with a row of that one.

### What it is worth on the JVM class output (phase 2)

The same products through `-o Prog.class`, run as `java Prog`, same machine, us per call.
**Best of three timed rounds after 400 warm-up products**, which the interpreter table
above is not -- and the difference matters at the small end: a single round at n=128 f64
measured 350-580 us where the best of three measures 50, because the device drops to its
IDLE CLOCK (208 MHz against 3003) between small calls and a cold round times the ramp.
That is a property of the machine, not of the backend -- the interpreter under the same
one-round harness reports the same inflated figures.

| n | `--simd` f64 | `--blas` f64 | `--gpu` f64 | `--simd` f32 | `--blas` f32 | `--gpu` f32 |
|---|---|---|---|---|---|---|
| 64 | 50 | **17** | 107 | 32 | **8** | 106 |
| 128 | 345 | 30 | **50** | 206 | 34 | **34** |
| 256 | 2613 | 170 | **145** | 1380 | 95 | **65** |
| 512 | 20760 | 1140 | **740** | 10480 | 530 | **210** |
| 1024 | -- | 6933 | **5367** | -- | 4433 | **2233** |
| 2048 | -- | 91750 | **39000** | -- | 44625 | **8375** |

**It is the interpreter's table, which is the finding.** Once the product is one device
call, the backend around it contributes nothing measurable -- exactly what `--blas` found
when its interpreter column landed on its JVM column. The `--gpu` crossover against a
20-core OpenBLAS sits between n=128 and n=256 at both widths, and the documented wart at
n=64 is if anything wider here (107 against 17) than the interpreter's.

**And the native binary's open item now has a workaround.** Measured with the same
harness: the native binary INTERPRETING `--gpu` costs 17440 us at n=512 f64 and 3795 at
n=256, against the compiled class's 740 and 145 -- 24x and 26x, 47x at n=512 f32. The
class the native binary EMITS is byte-for-byte the class `java -jar` emits and runs at the
JVM figures above. So on a `-Pnative` build, compiling the program is the way around the
per-call cost, and the item stays open only for the interpreter.

## Native image

Two build inputs, both already in
`src/main/resources/META-INF/native-image/am.ik.rontolisp/rontolisp/`:

- **`resource-config.json`**: `am/ik/gpu/gemm\.ptx`, conditional on `am.ik.gpu.CudaGemm`,
  beside the `--simd` and `--blas` template entries. Without it the binary probes, finds a
  GPU, and then fails to find its own kernels. Phase 2 added three more, conditional on
  `am.ik.rontolisp.codegen.jvm.JvmGpuRuntimeBuilder`: the template's own
  `JvmGpuTemplate\.class`, `am/ik/gpu/.*\.class` (the CLASS FILES, which the compiler
  reads as resources to embed them -- a native image carries none of that by default), and
  the PTX again under the compiler's condition, because a binary that only ever COMPILES
  never makes `CudaGemm` reachable. Verified: the native binary compiles a `--gpu` class
  and the class runs the device.
- **`reachability-metadata.json`**: a `foreign.downcalls` entry per distinct SIGNATURE --
  24 handles collapse to 15 shapes, added to the six `--blas` ones. Without them the
  binary binds the driver and then throws `MissingForeignRegistrationError` on the first
  call. Generate them with the tracing agent
  (`-agentlib:native-image-agent=config-output-dir=...`) over a program that opens the
  binding and runs a product, then fold the result in -- the agent traces
  `Linker.downcallHandle`, so merely constructing `CudaDriver` registers every shape.
  **A per-entry `"comment"` key is rejected by the schema** (`Unknown attribute(s)
  [comment] in foreign call`), which is why the signature-to-entry-point mapping lives in
  the file's top-level `comment` array instead.

Verified 2026-08-20: a `--no-fallback` image built with `--enable-native-access=ALL-UNNAMED
--add-modules jdk.incubator.vector -H:+UnlockExperimentalVMOptions -H:+VectorAPISupport`
-- the real binary's flags -- loaded the checked-in PTX, ran both kernels, took the
multi-chunk copy route at n=3072 and printed exactly what the JVM printed. So CUDA does
not re-enter todo-102's `VectorAPISupport` / `SharedArenaSupport` fight; nothing here
needs `Arena.ofShared`.

## Tests: the library

Mirrors `--blas`'s split exactly; the interceptor's own tests are listed above.

| what | where |
|---|---|
| needs a GPU on the machine (`@EnabledIf` on the probe) | `am/ik/gpu/GpuTest` |
| must hold on EVERY machine, GPU or not | `am/ik/gpu/GpuDeclineTest` |

`GpuTest` pins the checked-in PTX (it is a generated artifact with no other test of its
validity, and a bad regeneration would pass every decline test), the exactness of a
product over exact inputs at both widths, the tolerance over inexact ones, the operand
offsets, a rectangular shape that is not a multiple of the tile, the multi-chunk copy at
n=3072, and the no-leak assertion. `GpuDeclineTest` pins that the probe answers without
throwing, that every decline condition declines rather than throws, that the status table
is total, that only the context-destroying statuses are sticky, that the PTX is the
artifact the loader expects with its regeneration command still attached, and that
`useKernels` is accepted without probing. **That last test hands it the REAL checked-in
text and no test anywhere may hand it anything else**: the override is process-wide and
read at probe time, so a placeholder would decide what the whole suite's device compiles,
whichever class happened to run first.

Everything here is skipped on a machine without a GPU, which is every CI runner this
project has -- so `GpuDeclineTest` is the half that actually runs there, and it is the half
that must never regress.

## What is deliberately NOT here

No residency, no element-wise tier, no Metal. Those are todo-123's phases 3, 4b and 5, and
each of them needs this file's numbers before it starts -- phase 4b's case in particular
is now a measurement (above) rather than a guess: with the stacked product on the device,
about half of a transformer's training step is gone and the rest of it is element-wise. The per-call cost of an FFM downcall inside a native image is still unexplained
(above), and phase 3 must not quote a residency figure from that build without measuring
it first.
