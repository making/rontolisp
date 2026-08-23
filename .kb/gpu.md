# `--gpu`: a matrix product on the GPU, or a decline

Two layers, in one file. `am.ik.gpu` is the foundation (todo-123 phase 1, landed on its
own): a language-independent library that takes a matrix product and either runs it on a
GPU or answers `null`. **The `--gpu` flag on the INTERPRETER** (phase 1B) is the
first interceptor over it, and "The interception layer" below is its whole record -- the
per-backend touch points, the chain order, the precision contract and the test map. **The
JVM class output** (phase 2) is the second, and "The JVM backend" below is where its one
genuinely new decision lives: what the emitted `.class` carries. A `.wasm` output still
refuses the flag outright and always will. **The STACKED product** (phase 4a, 2026-08-21)
is the second intercepted member on both of those backends, and "The stacked matrix
product" below is its whole record: the batch kernel, the threshold decision it forced,
and why it was landed BEFORE residency. **The ELEMENT-WISE tier** (phase 4b, 2026-08-21)
is the third and it is twelve members, not one: "The element-wise tier" below is its whole
record -- which members the measurement chose, which it REFUSED and by what number, the
precision exception it forced (the first one a user can see with the naked eye), and the
residency measurement it hands to phase 3. **The STRIDED tier** (phase 3, 2026-08-21) is
the fourth and last, and it is what phase 3 turned into: residency was measured and
DECLINED, and the ten members whose CPU twin is a scalar ODOMETER walk rather than a lane
loop were taken instead. "The strided tier, and why residency was NOT built" below is its
whole record -- including the profile that says what a `--gpu` training step is now made
of, which is no longer `linalg:` at all. **METAL** (phase 5, 2026-08-21) is the second
DEVICE rather than a fifth tier: "The Metal backend" below is its whole record, and the
one thing to know before reading anything above it is that everything above it is CUDA.
Where the two disagree -- the width, three thresholds, one whole tier -- that section
says so and is the one that applies on a Mac. **The second profile (2026-08-22)** is the
round after the item closed: "The second profile, and the round it drove" below is its
record -- the generator fill became a device member (the one with no operand and a
byte-identical result), eleven boxed Lisp walks the profile named went onto the `--simd`
seam instead (`.kb/linalg-simd.md`), the per-call `cuMemGetInfo` was amortized, and the
host-memory copy route was measured and kept. It is also where the residency numbers were
re-derived. **DEVICE RESIDENCY (2026-08-22, todo-474)** is the round after that, and it is
the one that reversed two of this file's measured declines: "Device residency, built"
below is its record -- the weakly-keyed identity cache, the cap that decides whether it
pays, the invalidation every in-place write on both backends now performs, and the
finding underneath all of it, that a device copy to or from a FRESH Java array costs this
machine a hundred times a warm one, which is why every download is now staged through a
pinned bounce buffer after "the library never stages" had been measured and written down.
**THE GEMV (2026-08-22, todo-475)** is the round after that and the first member OUTSIDE
`linalg:`: "The GEMV, and the matrix that stays" below is its record -- why a
matrix-by-vector product pays on a device only when its matrix is already there, the
"offered twice, never written" rule that decides when a matrix is uploaded, the double
accumulator that puts the single-float result on the defun's own bits, the
`read-sequence` invalidation gap the round found and closed, and the llama2 measurement:
1.3x over a `--simd` that had itself become 2.5x faster than its README recorded.
**THE METAL HALF OF BOTH (2026-08-22, todo-477)** is the round after that, on an M4 Max:
"Residency and the GEMV on this backend" in the Metal section is its record -- the GEMV as
a member there from `2^21` with a COMPENSATED float accumulator that lands on the defun's
bits without a `double`, residency measured and kept for ONE kind of array (the GEMV's
matrix) because every other kind was slower resident than copied on unified memory, and
the finding that sets the ceiling on a decode loop there: the GPU's idle-clock penalty,
~0.5 ms on the first command buffer after a millisecond of quiet, which makes llama2 1.0x
on that machine with the flag and the story unchanged. **LAZY RESULTS (2026-08-23,
todo-491)** is the round after that, and it reversed the design todo-474 chose: "A result
comes home on first host touch" below is its record -- a member's result stays on the
device until the host first reads it, every host read of packed-array storage on both
backends is enumerated and materializes first (the mirror of the writer enumeration), the
cap no longer applies lazily (its first build, which kept it, was SLOWER than the eager
one, and the section says why), and the members this file had REFUSED as round trips --
the equal-shape and scalar binary ops, `sqrt` and its three siblings, `where`, the Adam
step, and the copies: `reshape`, `transpose`, `slice`, `concatenate`, `%la-scale` -- are
members over a RESIDENT operand, every one bit-identical to the CPU kernel it replaces.

**Every number below is re-derivable.** The probes are
`.todo/123-gpu-acceleration/{AllocatorCost,CopyRoute,WorthCrossover,ElementwiseCrossover,
StridedCrossover,ResidencyCost,FreshPageCost}.java` over the shared driver-only binding
`CuLib.java`, plus
`matmul-baseline-warm.lisp`, `elementwise-baseline.lisp` and `shaped-baseline.lisp` for
the CPU columns;
that directory's README says which answers which and records what they printed. They need
the driver and nothing else -- they load the kernels this library ships rather than
compiling any -- so they run wherever the feature does.

**The item itself is gone; that directory is not.** `.todo/123-gpu-acceleration.md` was
deleted when the last phase landed, so every `todo-123` citation below is to a text that
is now read back through `.todo/.history.md`'s row for it
(`git show <commit>~:.todo/123-gpu-acceleration.md`). The probes stay checked in where
they are, because this file's numbers are re-derived from them and its paths name them.

Read `.kb/linalg-simd.md` first for the declined-input protocol this is shaped for, and
`.kb/linalg-blas.md` for the flag whose posture it copies: **recommended, never required;
a machine without the hardware runs the same programs to the same output.** Everything
below is what is DIFFERENT about a GPU, and the differences are the fixed cost of a round
trip and the fact that the accelerator is a separate machine with its own memory.

## The invariant

**`am.ik.gpu` never throws and never signals.** Every failure -- no driver, no device, an
old card, a shape it cannot launch, a product too small to be worth the trip, device
memory exhausted, any `CUresult`, a command buffer that did not complete, an operand at a
width this device has no type for, a JVM that forbids native access, a platform with
neither `libcuda.so.1` nor `Metal.framework` -- is the same answer: `null`, and the caller
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
| `am.ik.rontolisp.codegen.jvm.JvmLinalgGpu` | which members the device bridge claims (fourteen), and each one's `ops` key |
| `am.ik.gpu.Gpu` | the whole public surface: `available`, `description`, `worth` (a product or a stack), `worthMap`, the `multiply` and `map` overloads, the `MAP_*` op codes |
| `am.ik.gpu.GpuDevice` | the sealed seam between the two backends: `supportsDouble`, `thresholds`, and the members |
| `am.ik.gpu.CudaGemm` | the probe, the context/module lifetime, the per-call product and the per-call map, the download bounce buffer |
| `am.ik.gpu.CudaResidency` | device residency: the weakly-keyed identity LRU from a host array to its resident copy, the pending-free list, and the GEMV's no-buffer "seen once" marks |
| `am.ik.rontolisp.FloatArrayAccessHook` | the interpreter's two seams: every packed-array store reports here BEFORE it lands, and every read of packed-array storage (the records' `data()` accessor) reports here first; `eval/LinalgGpu.install` points them at `Gpu.written` / `Gpu.materialize` |
| `am.ik.gpu.CudaDriver` | the FFM binding: `libcuda.so.1` and 24 downcall handles |
| `am.ik.gpu.CuResult` | every CUDA 13 status code, and which of them leave the context dead |
| `am.ik.gpu.MetalGemm` | the Apple half: the probe, the MSL library, MPS, the buffer pool, the per-call members |
| `am.ik.gpu.MetalDriver` | the FFM binding: `libobjc` + Metal + MetalPerformanceShaders, one handle per selector SHAPE |
| `src/main/resources/am/ik/gpu/gemm.cu` / `gemm.ptx` | the CUDA kernels, source and checked-in artifact |
| `src/main/resources/am/ik/gpu/gemm.metal` | the Metal kernels -- the whole artifact, compiled at run time |

## The API

```java
static boolean available()                       // does this machine have one
static String  description()                     // what was found, or why nothing was
static boolean worth(long n, long m, long p)              // is this product big enough to offer
static boolean worth(long batch, long n, long m, long p)  // ... is this STACK of them
static boolean worthMap(long n)                  // ... is this ELEMENT-WISE map
static void    useKernels(String ptx)            // for an embedder that has no resources
static void    useMetalKernels(String msl)       // ... the same, for the Apple half
static double[] multiply(double[] a, int offsetA, double[] b, int offsetB, int n, int m, int p)
static float[]  multiply(float[]  a, int offsetA, float[]  b, int offsetB, int n, int m, int p)
static boolean  multiply(double[] a, int oA, double[] b, int oB, double[] out, int oOut, int n, int m, int p)
static boolean  multiply(double[] a, int oA, int strideA, double[] b, int oB, int strideB,
                         double[] out, int oOut, int batch, int n, int m, int p)
static boolean  map(int op, double[] a, int oA, double[] out, int oOut, int n)
static boolean  map(int op, float[]  a, int oA, float[]  out, int oOut, int n)
static boolean  worthStrided(long n)             // ... is this BROADCAST or GATHER
static boolean  worthFold(long n)                // ... is this AXIS FOLD
static boolean  bcast(int op, double[] a, int oA, int[] sA, double[] b, int oB, int[] sB,
                      double[] out, int oOut, int[] dims)
static boolean  gather(double[] a, int oA, int[] sA, double[] out, int oOut, int[] dims)
static boolean  fold(int op, double[] a, int oA, double[] out, int oOut,
                     int outer, int len, int inner)
static boolean  worthMatvec(long rows, long cols) // ... is this MATRIX-BY-VECTOR product (once its matrix is resident)
static boolean  matvec(double[] w, int oW, double[] x, int oX, double[] y, int oY, int rows, int cols)
static void     written(Object hostArray)       // a packed array is ABOUT TO BE written in place: its resident copy is stale (and comes home first if it was the only one)
static void     materialize(Object hostArray)   // a packed array is about to be READ on the host: a lazy result comes home
static boolean  resident(Object hostArray)      // does the device hold a copy of it
static void     lazyResults(boolean on)         // results stay on the device until materialized (the interceptors' mode; off by default)
static boolean  zip(int op, a, oA, b, oB, out, oOut, n)            // equal-shape binary op, over a resident operand only
static boolean  scale(int op, a, oA, double s, boolean swap, out, oOut, n)   // array-with-scalar form, resident only
static boolean  where(m, oM, sM, ms, x, oX, sX, xs, y, oY, sY, ys, out, oOut, dims)  // the three-way select, resident only
static boolean  adamStep(x, oX, g, oG, m, oM, v, oV, n, double[] rule)      // Adam IN PLACE, resident only
static boolean  copy(a, oA, sA, spanA, out, oOut, sOut, spanOut, dims)      // the strided copy: reshape / transpose / slice / concatenate, resident only
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

**`map` is the element-wise tier and its member is a PARAMETER**, one of the twelve
`Gpu.MAP_*` op codes, which `gemm.cu`'s kernel switches on -- one entry point per width
however the member set grows. An op code the library does not name is a decline like any
other, and the kernel's own `default` is the identity rather than a member, so a mirror
that ever slipped cannot silently answer some other function.

**`written` and `materialize` are the two halves of residency's CONTRACT on the caller, not
conveniences.** Every member keeps a copy of each operand and result on the device, keyed by
the identity of the host array, and reads that copy instead of uploading again -- so every
in-place write to a packed array's storage must come through `written` BEFORE it lands, or
the next call answers for bytes the array no longer holds; and since todo-491, with
`lazyResults` on, a result STAYS on the device until the host first reads it, so every
host read of packed-array storage must come through `materialize` first, or it reads the
zeros of an array nobody filled. The enumerations -- the writers in "Device residency,
built", the readers in "A result comes home on first host touch" -- are pinned by a test
on each backend. Both hooks are cheap when they do not matter (a volatile read with nothing
resident or nothing dirty, and one identity compare for a loop over one array), never run
the probe, never throw, and take any object; `materialize` is the one that cannot decline
when it does matter.

**The offsets are mandatory, not a convenience.** The compiled backends keep a
`[rank, dim..., data...]` header inside the same array as the data, so an interceptor on
the JVM must be able to say where the elements start; the interpreter passes 0. The result
carries no header, so the caller wraps it.

## The runtime requirement is `libcuda.so.1`, and nothing else (on Apple, nothing at all)

`SymbolLookup.libraryLookup("libcuda.so.1", Arena.global())` plus a `downcallHandle` per
entry point. No JNI, no bundled shim, no Java library, and **no CUDA toolkit**: no
`libnvrtc`, no `libcudart`, no `libcublas`. `libcuda.so.1` ships with the NVIDIA driver, so
"has a working GPU" is the entire runtime requirement, and that is what makes this
compatible with the no-external-dependencies rule rather than a compromise on it.

The spike bound NVRTC to compile CUDA C at run time. This does not, and must not: NVRTC is
in the toolkit.

The Apple half's answer to the same question is one step shorter and is in "The Metal
backend" below: the frameworks and the MSL compiler are both in the OS, so there is no
driver to require either.

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
- The product kernel was the spike's 16x16 tiled GEMM at f32 and f64, unchanged, until
  2026-08-22; it still is at f64 -- there it MATCHES cuBLAS (0.9x, both pinned by the same
  scarce fp64 units) -- and at f32 it is now one of three: the 16x16 kernel for everything
  small and two REGISTER-TILED siblings, `gemm_batched_f32_t4` (64x64 block tile, 4x4
  outputs per thread) and `gemm_batched_f32_t8` (128x128, 8x8), chosen per shape by the
  SM count and bit-identical to it ("The register-tiled f32 GEMM" below). The 7x cuBLAS
  had on kernel time at f32 is now about 2x, and nothing here opens `libcublas`.
- **Eighteen entry points since 2026-08-22** (sixteen that morning, twelve since phase 3): `gemm_f64` / `gemm_f32`, the stacked siblings
  `gemm_batched_f64` / `gemm_batched_f32`, the element-wise `map_f64` / `map_f32`, the
  strided `bcast_*` / `gather_*` / `fold_*`, the generator `rng_fill_f64` /
  `rng_fill_f32` -- that pair is the Wichmann-Hill walk spelled in `_rn` intrinsics
  (so nvcc cannot contract `lo + span * u` into an FMA) behind a square-and-multiply jump,
  and the PTX grew from 113 KB to 143 KB for it -- and, the same day, the GEMV
  `gemv_f64` / `gemv_f32` behind `vec:matvec` (one warp per row, a double accumulator at
  both widths; 143 KB to 152 KB), and that evening the two register-tiled f32 products
  (152 KB to 204 KB, with no `#pragma unroll`: ptxas unrolls the 16-deep k loop itself,
  measured at the same speed, and the pragma alone was another 60 KB of PTX). A batched kernel is six lines -- it offsets the
  three pointers by `blockIdx.z` times the strides and calls the SAME `gemm<T>` device
  function -- which is why a batched cell folds `k` bit-identically to an unbatched one
  and the precision contract below needed no second sentence. `gemm.cu` grew by 22 lines
  and the PTX by 354; the regeneration command is unchanged, and `nvcc` emits the batched
  entries after the plain ones. **Phase 4b then grew the PTX from 20.3 KB to 86.9 KB**
  (716 lines to 2950), which is the element-wise tier's whole cost and is discussed with
  the blob below -- the map kernels are two entry points but twelve inlined libm bodies
  per width -- **and phase 3's strided tier from 86.9 KB to 113 KB** (2950 lines to 4124)
  for its six, which is cheap per entry point by comparison: they are integer index
  arithmetic and one arithmetic op.
- **`Gpu.useKernels(String)` supplies the text for an embedder that carries the library's
  CLASSES but not its resources**, and is read by the probe ahead of the resource. It
  exists for exactly one caller -- the JVM backend, whose emitted class renames these
  classes into its own package where a classpath resource of ours cannot follow (below).
  A call after the probe has run changes nothing and is not an error.

## The availability probe

One probe per process, cached, in `Gpu`'s static initializer, and it answers on every
machine without throwing. CUDA is tried first and Metal second (see "The dispatch seam"
below); this is the CUDA one:

1. `CudaDriver.open()` -- the library lookup. Absent driver, wrong platform, forbidden
   native access, or a driver too old to export an entry point: `null`, and the answer is
   "this machine has no NVIDIA driver".
2. `cuInit`, `cuDeviceGetCount`, `cuDeviceGet`.
3. compute capability `>= 7.5`, checked explicitly so the reason is legible rather than a
   `CUDA_ERROR_NO_BINARY_FOR_GPU` from the module load.
4. `cuDevicePrimaryCtxRetain` + `cuCtxSetCurrent`.
5. the PTX resource, `cuModuleLoadData`, and `cuModuleGetFunction` for all six kernels.
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
- **Per call, three device buffers, freed on every path** -- two for an element-wise map,
  which has one operand; FOUR for a broadcast binary op and three for a gather, whose
  extra one carries the layout -- success, decline and failure alike, in a `finally`. Two tests pin it and they are not the same test:
  `aRunOfSuccessfulProductsFreesEveryBufferItAllocates` runs 1000 products that WORK, and
  `aDeclinedProductCostsTheDeviceNothing` runs twelve that FAIL, which is the path the
  first one never enters and the one that was wrong. Both assertions are two-sided --
  free memory that GREW would mean the test is measuring the rest of the machine. The
  first one's bound is deliberately LOOSE (256 MB against the 1.5 GB a leak costs):
  `cuMemGetInfo` is a property of the device, not of the thread, and since phase 2 the
  JVM backend's tests run in a second surefire fork where every compiled class defines
  its own copy of this binding and loads its own module. It was 64 MB over 500 products
  and that is too tight to survive a parallel fork -- measured, 159 MB of drift.
- **Since 2026-08-23 a RESULT also stays UNREAD on the device until the host first touches
  it** (`Gpu.lazyResults`, the interceptors' mode; "A result comes home on first host
  touch" below), and the 1 GB cap does not apply in that mode.
- **Since 2026-08-22 the operands and the result of every call STAY on the device** --
  a copy of each, keyed by the identity of the host array and held weakly, an LRU against
  a budget that is the smaller of a quarter of free memory and 1 GB, freed when the host
  array is collected, written, evicted or released (`CudaResidency`; "Device residency,
  built" below is the record, including why the first two versions of it made the
  program SLOWER). Phase 3 had measured residency and declined it; the second profile
  re-derived the ceiling and todo-474 built it. A dropped buffer is never freed from the
  write path: it is queued and freed at the start of the next call, before any operand is
  looked up, or at the end of one, after the launch and the download -- the one ordering
  that keeps a stream-ordered free behind the kernel that reads the buffer.
- **Threads.** The driver API is thread-safe and every call owns its buffers, so concurrent
  products are correct without a lock; they serialize on the device anyway, because
  everything goes to the null stream. The one caveat a future interceptor should know: a
  copy issued while ANOTHER thread's kernel is still queued on the null stream waits for
  it, and waits for it INSIDE the critical window. Per-thread streams are the fix; nothing
  in the feature is threaded today, so it stays open.

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
   grows the pool at all. It cost 0.6 us on a call that is going to succeed when it was
   measured cold; **in a training step it measured 6-13 us a call** (nsys, 7060 calls,
   91 ms of a 6.5 s run), so since 2026-08-22 it is AMORTIZED: the answer is remembered,
   decremented by what was handed out, and re-asked every 64 allocations or as soon as a
   request is more than a quarter of the remembered figure (`CudaGemm.allocate`). The
   guard is unchanged -- an estimate that errs does so towards REFUSING, and a request the
   stale figure lets through that the device then refuses still lands on the trim below.
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
wins, and the library never stages -- FOR THE UPLOAD, and over a host array the GPU has
touched before.** That table copied to and from the same host array on every iteration.
Since 2026-08-22 every DOWNLOAD is staged through one pinned 16 MB bounce buffer the probe
allocates once (`cuMemHostAlloc`; a plain non-critical `cuMemcpyDtoH` into it, then a Java
copy into the result array, in 16 MB chunks), because a result array is a FRESH Java
array and on this machine a device copy into a page the GPU has never touched costs a
hundred times a warm one -- "Device residency, built" below has the probe and the
numbers. The upload stays direct: its source was just written by the CPU, and a CPU
write is what makes a page warm.

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

**The element-wise tier's divergence has a different CAUSE and it is bigger.** A product
differs from a scalar one by a rounding mode (fused, once instead of twice). A
transcendental differs because the device has ITS OWN LIBM: two correct implementations of
`erf` may differ in their last ulps and there is no sense in which either is wrong. And at
f32 there is a second cause on top: the device kernel evaluates AT the operand width
(`expf`), where every CPU kernel in this project evaluates in double and narrows on the
store, because `emap`'s rule says so. Evaluating in double on the device instead was
considered and refused: an f64 transcendental costs a consumer card 32-64x an f32 one, so
following the CPU's rule would make the width the hardware is FOR the slower of the two.
The measured consequence is in the interceptor's precision contract below, per member.

Read the last two columns together: **at f32 the divergence is the WIDTH, not the GPU** --
a CPU f32 accumulation of the same product lands at the same distance from the f64 oracle,
which is the reading the Metal spike reached on completely different hardware.
`.kb/linalg-simd.md`'s single-precision reduction contract already covers that case. At f64
the tiled kernel lands within 6e-16 relative, which is close but is still a break with the
bit-identity `#d` has under `--simd` -- and that is a decision the interceptor must make
when it lands, not this library.

## The Metal backend: the same flag on Apple Silicon (phase 5, 2026-08-21)

Everything above this line is CUDA. `--gpu` reaches a second kind of device since phase 5,
and the honest summary is that it is the same feature with a different member set: the
flag, the CLI, the interception layer, the decline protocol and the tests are shared, and
what is NOT shared is the width, three of the thresholds and one whole tier.

| | CUDA | Metal |
|---|---|---|
| widths | `#d` and `#f` | **`#f` only** -- MSL rejects `double` outright |
| rank-2 product | our tiled kernel | **MPS** above `2^27` per matrix, our tiled kernel below |
| stacked product | our batched kernel | our batched kernel |
| element-wise tier | twelve members | the same twelve |
| broadcast + axes transpose | yes | yes |
| axis fold (`sum`/`amax`/`amin` `:axis`) | yes | **not as a round trip, measured**; over a resident operand only (the sum in software binary64) |
| `vec:matvec` (GEMV, matrix resident) | from `2^17`, double accumulator | from **`2^21`**, **compensated float** accumulator |
| resident set | every operand and result | **the GEMV's matrix only, measured** (lazy results are built and pinned but measured not to pay: the interceptors stay eager) |
| product threshold | `2^17` | `2^22` |
| element-wise threshold | `2^14` elements | `2^17` |
| broadcast / gather threshold | `2^15` output elements | `2^18` |
| per-call floor | 16-18 us | **77 us**, per COMMAND BUFFER |
| per-call memory | the driver's pool | **our own** size-classed buffer pool |
| kernels | PTX generated at build time, checked in | MSL compiled at RUN time, from a string |

### The dispatch seam

`GpuDevice` is a package-private sealed interface over `CudaGemm` and `MetalGemm`, and
`Gpu` is unchanged above it. Two questions cross it that did not exist before:
`supportsDouble()` -- so a `#d` operand is a decline rather than a slower path -- and
`thresholds()`, because a 16 us floor and a 77 us floor do not accept the same shapes and
a single constant would have been wrong on one of the two.

`Gpu.Probe` asks CUDA first and Metal second. That is not a preference: no machine has
both `libcuda.so.1` and `Metal.framework`, and each declines in a failed library lookup on
the other's platform, so the order costs a `dlopen` that was going to fail anyway. What it
does decide is which SENTENCE a machine with NEITHER gets, and there the platform picks --
"`libcuda.so.1` is not present" is noise on a Mac.

### The runtime requirement is the OS, and there is nothing else at all

`SymbolLookup.libraryLookup` on `/usr/lib/libobjc.A.dylib`, `Metal.framework` and
`MetalPerformanceShaders.framework`, and one `downcallHandle` per distinct C SIGNATURE.
No JNI, no Swift shim, no bundled artifact, and -- the Apple counterpart of "no CUDA
toolkit" -- **no Xcode**: the frameworks and the MSL compiler both ship with macOS. So the
CUDA half's "a working GPU is the entire runtime requirement" is, here, "a Mac".

`MTLCreateSystemDefaultDevice` is the only C entry point in Metal; everything else is
`objc_msgSend`. Apple's own arm64 rule is that it must be CALLED through a prototype
matching the selector rather than as the variadic it is declared as, which is what a
`FunctionDescriptor` without `firstVariadicArg` produces -- so `MetalDriver` holds one
handle per SHAPE and a selector is never sent through the wrong one. A selector taking an
`MTLSize` needs the struct layout, and sending it through a `long` shape is an immediate
SIGBUS rather than a wrong answer.

**The kernels compile at run time, from a string, and that is better than the PTX story
rather than merely equal to it.** `newLibraryWithSource:options:error:` takes
`gemm.metal` verbatim: no generated sibling to check in, no toolchain to run, nothing
pinned to a virtual architecture. Measured ~35 ms the first time a given text is ever seen
on a machine and 2-3 ms on every later process, because the OS caches it the way the
NVIDIA driver caches PTX. `MTLCreateSystemDefaultDevice` (12-15 ms) is the real cost of
the probe.

**`MTLMathModeSafe` is set explicitly**, falling back to `setFastMathEnabled:NO` on an OS
whose `MTLCompileOptions` predates it. That is not a preference: the relaxed default
flushes denormals and reassociates, and the strided tier below claims BIT-IDENTITY with
the scalar defun, which neither survives.

**Every call pushes an autorelease pool.** A command buffer, an encoder and every
`MPSMatrixDescriptor` are autoreleased objects; without a pool per call they accumulate
for the life of the process. `objc_autoreleasePoolPush`/`Pop` measures 0.0 us, so this
costs nothing and its absence would be a slow leak rather than a failure.

### Single float, or nothing

MSL rejects `double` outright -- `error: 'double' is not supported in Metal` -- so
`MetalGemm.supportsDouble()` is `false` and every double-taking method answers `false`
without touching the device. There is no fp64 on this hardware to fill the gap with later.

Two consequences worth stating plainly. **The decline protocol is load-bearing in a way it
is not on CUDA**: `linalg`'s default width is double, so on Apple the flag is inert until
a program reaches `#f` data -- which `torch:` does by default since phase 0, and which a
`linalg`-only program has to ask for. And **`GpuTest` no longer describes both backends**:
it is gated on a double-capable device now, and `MetalGpuTest` answers the same claims at
`#f`. The two are separate files because the two devices do not have the same member set,
the same thresholds or the same precision story, so one width-generic suite would have had
to branch on the backend in nearly every test.

### The rank-2 product goes through MPS, and the stack does not

`MPSMatrixMultiplication` is in the OS, so every argument that killed cuBLAS is absent
here: no toolkit to require, no f64 regression to weigh, and -- measured -- no precision
cost either. It is four more `objc_msgSend` signatures.

Which route runs is a pure SIZE decision, `n * m * p >= 2^27` for ONE matrix of the
product. Measured on an M4 Max, f32, us per call, both with their host copies and with the
buffer pool warm (`.todo/123-gpu-acceleration/MtlBreakdown.java`):

| n | our tiled kernel | MPS |
|---|---|---|
| 128 | **144** | 172 |
| 256 | **166** | 180 |
| 384 | 201 | **198** |
| 448 | **245** | 265 |
| 512 | 308 | **202** |
| 768 | 824 | **337** |
| 1024 | 1545 | **523** |
| 2048 | 10183 | **2264** |

MPS carries ~35 us of object churn a call (a descriptor is 2.4 us, an `MPSMatrix` 0.25, an
`MPSMatrixMultiplication` 4.0), which is why it loses below n≈448 and wins by 1.5-4.5x
above it. The threshold sits at `2^27` (n=512), where the win is 1.5x and unambiguous.

**The STACK stays on our kernel whatever its size, and the reason is the zero stride.** A
broadcast operand -- the rank-2 weight matrix under a `(B T C)` activation, which is every
`torch:linear` -- passes a per-batch element stride of 0, and a batched
`MPSMatrixDescriptor` cannot be handed that. What MPS CAN serve is one encode per slab
into one command buffer, and above `2^27` per matrix that is what `multiplyThroughMps`
does: Metal's floor is per command buffer rather than per dispatch, so the encodes share
the one wait, and `MPSMatrix` takes a byte offset so each slab addresses itself.

**The two routes agree BIT FOR BIT**, which is what lets the choice be invisible: 0 of 703
cells differ on a rectangular 37x23x19 product and 0 of 262144 at n=512
(`MetalGpuTest.bothProductRoutesComputeTheSameProduct`, which flips the route with a test
hook because MPS otherwise makes the tiled kernel unreachable above the threshold on every
machine that has Metal). It also means `rowBytes` may be passed as `columns * 4` rather
than through `rowBytesFromColumns:dataType:`, which PADS (80 bytes for 19 columns) and
would not describe our contiguous row-major data.

### THE AXIS FOLD IS NOT A MEMBER HERE, and that is two measurements

`Gpu.fold` declines on this backend at every width and every size, and either half of the
reason would be enough on its own.

1. **`%la-fold-axis` accumulates in `double` at BOTH widths** (`JvmSimdVectorTemplate`
   says so in as many words, and `gemm.cu`'s fold kernel mirrors it). No float accumulator
   reproduces that, so a Metal `sum :axis` could not be bit-identical the way the
   broadcast and the gather are -- and over a 256-long axis the divergence would be
   ~n*eps, which is 1e-5 relative, not a last-ulp difference.
2. **The amax/amin half, which needs no accumulator and WOULD have been exact, does not
   pay.** Measured on an M4 Max at f32, us per call: the CPU fold is 85 over 262144
   elements and 410 over 1048576, against this backend's ~150 and ~380 for the same
   shapes. A tie at best, and a tie is a decline.

`mean`, `var`, `std`, `linalg:softmax` and `linalg:log-softmax` therefore reach the device
on Apple through their broadcast and element-wise links only -- and still by 1.5-2.9x, per
the table below. `MetalGpuTest.theAxisFoldIsDeclinedAtEveryWidthAndSize` is the guard, and
`gemm.metal` has no `fold_f32` entry point at all, so a mirror cannot silently reappear.

### This backend owns a buffer pool, and the CUDA one does not

`CudaGemm` allocates per call because the DRIVER has a stream-ordered pool behind
`cuMemAllocAsync`. **Metal has none**, and the cost is not what a microbenchmark of
`newBufferWithLength:options:` says. Measured on an M4 Max: the allocate/release pair
itself is 1.2 us at 4 KB, 2.7 at 1 MB and 7.7 at 16 MB -- cheap -- but the pages fault in
on the FIRST WRITE, and a whole product pays for that:

| n, f32 square product | fresh buffers | pooled buffers |
|---|---|---|
| 128 | 186 us | **144** |
| 256 | 303 | **166** |
| 512 | 506 | **308** |
| 1024 | 2496 | **1545** |
| 2048 | 13664 | **10183** |

So the buffers are size-classed by power of two (floor 4 KB), reused, and bounded by a
quarter of `recommendedMaxWorkingSetSize`. **This is sound with no invalidation rule of
any kind, which is what separates it from residency**: they are SCRATCH -- fully
overwritten on the way in, fully read on the way out -- and no host array's device copy
outlives the call. (Since todo-477 ONE kind of slab is taken out of the pool and held
under the invalidation rule: the matrix of an accepted GEMV, "Residency and the GEMV on
this backend" below. Everything else is still scratch, and that section says why.)

The leak question changes shape with it: not "is every buffer freed" but "does the pool
reach a steady state", which `MetalGpuTest.aRunOfCallsSettlesTheBufferPoolRatherThanGrowingIt`
asserts over 400 products after a warm-up. `freeDeviceMemory()` is
`recommendedMaxWorkingSetSize` less `currentAllocatedSize`, since Metal reports what this
process holds rather than what the device has left.

### The three thresholds, re-derived rather than inherited

Every one of them is measured against `--simd` on this machine at f32, because that is the
column the device has to beat and the CUDA constants were derived against a different CPU
and a five-times-lower floor.

**The product: `2^22`** (a 166x166x166 product). Interpreter, us per call, warm, best of
three:

| n | `--simd` | `--gpu --simd` | |
|---|---|---|---|
| 48 | 16.0 | 16.0 | declined |
| 64 | 30.0 | 33.0 | declined |
| 96 | 85.0 | 88.5 | declined |
| 128 | 178.0 | 183.0 | declined (`2^21`, one power under) |
| 192 | 571.0 | **130.5** | 4.4x |
| 256 | 1287.5 | **141.0** | 9.1x |
| 384 | 4190.0 | **210.0** | 20x |
| 512 | 9975.0 | **220.0** | **45x** |

Read the declined rows too: the flag costs 3-5 us on a call it turns down, which is the
`worth` check and the operand unwrap, and is the same order the CUDA half pays.

**The element-wise tier: `2^17` ELEMENTS.** JVM class output, f32, us per call:

| n | `exp` CPU / device | `erf` CPU / device | `sin` CPU / device |
|---|---|---|---|
| 16384 | **70** / 75 | **565** / 695 | **45** / 55 |
| 65536 | **270** / 300 | 2760 / 2760 | **210** / 210 |
| 262144 | 1095 / **200** | 9040 / **245** | 760 / **200** |
| 1048576 | 4450 / **500** | 36650 / **650** | 3050 / **550** |

(The rows at and below 65536 are the device DECLINING, so both columns are the CPU and the
difference is the flag's own per-call cost.) At 262144 the cheapest member taken is 3.8x
and the dearest 37x; at the threshold itself the cheapest is ~2.5x, which is where
"unambiguous" lands with this floor.

**The broadcast and the axes transpose: `2^18` OUTPUT elements.** Below it the CPU wins or
draws: a broadcast `sub` is 455 us against ~260 at 262144 (1.75x) and 235 against ~165 at
131072, which is inside the noise.

### What it is worth, at a transformer's own shapes

JVM class output, f32, us per call, `--simd` against `--gpu --simd`, at the shapes
`train-gpt-soseki.lisp` produces at the notebook's own settings (batch 4, block 256,
n-embd 384, 2 heads). `shaped-baseline.lisp` and `elementwise-baseline.lisp`, same file
under each flag:

| f32, us/call | `--simd` | `--gpu --simd` | |
|---|---|---|---|
| `erf` (4 256 1536) -- the exact `gelu` | 56700 | **950** | **60x** |
| `exp` (4 256 256) | 752 | **152** | 4.9x |
| `bcast sub` (4 256 256) - (4 256 1) | 475 | **155** | 3.1x |
| `bcast div` (4 256 256) / (4 256 1) | 470 | **157** | 3.0x |
| `bcast mul` (4 256 384) * (384) | 720 | **245** | 2.9x |
| `bcast sub` (4 256 384) - (4 256 1) | 727 | **262** | 2.8x |
| `softmax :axis -1` (4 256 256) | 1982 | **685** | 2.9x |
| `log-softmax :axis -1` (4 256 256) | 1980 | **812** | 2.4x |
| `var :axis 2` (4 256 384) | 1315 | **852** | 1.5x |
| `amax :axis` / `sum :axis` (4 256 256) | 85 / 150 | 82 / 152 | DECLINED: not a member here |
| `mean :axis 2` / `sum :axis 0` (4 256 384) | 260 / 225 | 265 / 242 | DECLINED: a fold |
| `transpose '(0 2 1)` (4 256 192) | 357 | 397 | DECLINED: 196608 < `2^18` |
| same-shape `sub` (4 256 384), `mul` (4 256 1536) | 55 / 190 | 57 / 200 | DECLINED, as on CUDA |

Two things to read out of it. **`erf` is where this flag lives on Apple** -- 60x, and it is
the exact `torch:gelu`, so a transformer's slowest single member. And **`softmax` is 2.9x
with its `amax` and its `sum` still on the CPU**, which is what the fold's absence costs:
on CUDA the same chain is 4.8x with every link on the device.

**A declined call costs a little more here than on CUDA, and the reason is `worth`.**
`Gpu.worthStrided` is the probe-free pre-check and answers with the CUDA constant
(`2^15`), so between `2^15` and this backend's `2^18` an interceptor derives the broadcast
strides or the permutation -- two `int[]` -- and then the library declines the call
anyway. That is the band the `transpose` row above sits in, and it is what its +40 us is.
It could be removed by letting `worth` consult the threshold IN FORCE once the probe has
run (which on the `--gpu` path it always has), and that was weighed and not taken: it
would make a documented, deliberately probe-free predicate answer differently depending on
whether something else had touched the driver first, and `GpuDeclineTest` pins its answer
against the constant on every machine. Revisit with a measurement, not with this
paragraph.

The axes transpose at the notebook's own `(4 256 192)` falls just under the threshold
(196608 against 262144) and declines. That is the threshold doing its job -- at that size
the margin is ~1.6x, inside what the rule this file follows calls noise -- but it is the
one member whose Apple threshold most nearly excludes the shape it was taken for, and it
is the first thing to re-measure if the floor ever moves.

### Precision on this backend, and one bug that was fixed rather than tolerated

**The strided tier is still bit-identical to the scalar defun, and here that is an
ARGUMENT rather than an inheritance.** `gemm.cu` computes in `double` and narrows on the
store, which is `%la-bcast-loop`'s rule; MSL has no double to do that with, so
`gemm.metal` computes in `float` and the claim has to be earned:

- `+`, `-` and `*` over two floats are EXACT in binary64, so rounding the exact result
  once to float -- which is what a float operation does -- is exactly what
  compute-in-double-then-narrow produces.
- `/` is the double-rounding case and it is innocuous at these widths: binary64 carries 53
  bits and 53 >= 2 * 24 + 2, which is the classical bound under which rounding to the
  intermediate width and then to the target agrees with rounding once.
- The two strict selects and the gather move values, so nothing rounds at all.

`MetalGpuTest.theStridedTierIsBitIdenticalToTheScalarOracle` asserts it over inexact data
at every op, and the interpreter's and the compiled suite's own strided tests do it
through the language.

**The element-wise tier diverges, as it does on CUDA, and by about the same amount --
after two members were FIXED.** Measured over 262144 samples per member across each
member's own domain, against the f64 oracle narrowed to float:

| member | worst relative | member | worst relative | member | worst relative |
|---|---|---|---|---|---|
| `exp` | 3.2e-7 | `tan` | 3.3e-7 | `atan` | 2.2e-7 |
| `log` | 2.3e-7 | `sin` | 1.8e-7 | `sinh` | 3.0e-7 |
| `tanh` | 2.7e-7 | `cos` | 1.7e-7 | `cosh` | 2.8e-7 |
| `erf` | 9.7e-7 | `asin` | 2.3e-7 | `acos` | 2.8e-7 |

**`tanh` and `sinh` measured 1.8e-4 and 3.1e-4 before the fix, and THAT is the 4.87e-5 the
spike feared** -- this file's "does not reproduce, do not quote it again" was a CUDA
finding and does not carry here. Both of MSL's own carry an absolute error floor of
~3.4e-8 near zero, which is what an exp-based formula cancelling looks like, and the
relative error grows without bound as x -> 0. Both are odd with an `x + O(x^3)` expansion,
so `gemm.metal` takes the Maclaurin series to `x^9` below |x| = 1/4 (exact to ~1e-11
relative there) and the builtin above it, where its absolute floor is already under 1.4e-7
relative. The other ten needed nothing.

**`erf` has no builtin at all on this device** -- MSL does not define it -- so
`gemm.metal` runs `linalg::%la-erf-1`'s OWN series (A&S 7.1.6) at float width. That makes
the Metal `erf` CLOSER to the oracle than the CUDA one, which calls a device libm that is
a different algorithm and lands ~4.5 ulps away at f64.

**The product is the f32 story and nothing new**: 3.2e-7 relative from the f64 oracle at
n=208, which is what f32 costs and what a CPU f32 accumulation of the same product also
costs. `.kb/linalg-simd.md`'s single-precision reduction contract already covers it.

### Residency and the GEMV on this backend (2026-08-22, todo-477)

The two CUDA rounds above this one -- residency (todo-474) and the GEMV (todo-475) --
were built on a Linux box and left the Metal half declining `vec:matvec` at every size,
because a GEMV pays only over a resident matrix and this backend kept no resident copies.
This round is their Apple half, measured on an M4 Max (macOS 26.3.1, Oracle GraalVM
25.0.3), and three of its four findings are this platform's own.

**The accumulator: compensated, and on the defun's bits without a `double`.** gemm.cu sums
in double at both widths and narrows on the store, which is the scalar `vec.lisp` defun's
rule and what puts the CUDA result on its bits (1024 of 1024 rows). MSL has no double, and
a plain float sum (the `--simd` lane kernel's width, a different order) lands on 229 of
1024 rows (worst 2.9e-7). So `gemv_f32` keeps its running sum as a float-float PAIR: the
product's rounding error recovered exactly with an fma (`p = a*b; pe = fma(a, b, -p)`),
every addition a TwoSum whose error term goes into the low half, and the SIMD-group fold
the same pair-wise. The pair carries ~48 bits against a double's 53, and measured at
1024x768 over inexact data (`MtlMatvecCrossover.java`) it is bit-identical to the
double-accumulated oracle on **1024 of 1024** rows, with and without
`#pragma METAL fp contract(off)` (the pragma is kept: it is what makes the error-free
transforms mean what they say, and the Metal compiler's default happened not to contract
across statements). It costs nothing the memory-bound pass can see -- the same ~90 us a
resident call as the plain sum. The interceptor suites' `(probe) (probe)` test -- the
defun's 16778240 against the lane kernel's 16777984 -- therefore prints the same
`(16777984 16778240)` under `--gpu --simd` on a Mac as on the GB10, from a kernel with no
double in it.

**The threshold: `2^21`, re-derived; the cold trip never.** `matvec-baseline.lisp` under
`--simd` on this machine's JVM (the CPU column; this CPU is 1.5-2x the GB10's at every
shape) against `MtlMatvecCrossover.java` (shipped route, pooled shared-storage buffers,
one command buffer a call), f32, us per call, best of many:

| rows x cols | elements | `--simd` | device cold | **resident** | kernel only |
|---|---|---|---|---|---|
| 256x256 | 65536 | **5.5** | 85.2 | 80.9 | 83.1 |
| 288x288 (llama2 q/k/v/o) | 82944 | **7.5** | 86.6 | 78.2 | 76.1 |
| 512x512 | 262144 | **25.0** | 99.7 | 77.7 | 80.0 |
| 768x288 (llama2 w1/w3) | 221184 | **15.0** | 99.0 | 77.8 | 84.0 |
| 768x768 | 589824 | **60.0** | 128.8 | 90.5 | 85.5 |
| 1024x1024 | 1 M | 100.0 | 160.7 | 90.1 | 77.4 |
| 1448x1448 | 2.1 M (2^21 - 448) | 233.3 | 228.8 | **93.4** | 96.5 |
| 1536x1536 | 2.4 M | 266.7 | 243.1 | **94.4** | 92.9 |
| 2048x2048 | 4.2 M | 500.0 | 364.5 | **104.8** | 100.8 |
| 32000x288 (llama2 head) | 9.2 M | 800.0 | 753.0 | **185.2** | 176.7 |
| 4096x4096 | 16.8 M | 2333.3 | 1310.4 | **249.1** | 273.8 |

Read the "kernel only" column first: it IS the ~77 us command-buffer floor until the
matrix is several megabytes, "resident" adds the x copy and the y copy and sits within
noise of it, and "cold" adds a memcpy of the whole matrix -- on unified memory an upload
is a memcpy of the very bytes the CPU kernel would have streamed, so **the cold trip
cannot pay here at any size** (753 against 800 at the head) and the two-sight rule is not
a refinement on this backend but the member. The crossover against the CPU is therefore
the floor's: 1024x1024 is a tie (100 against 90), and `2^21` is where the margin is past
the noise -- 2.5x at 1448x1448 (448 elements short of it, the measured edge), 2.8x at
1536x1536, 4.8x at 2048x2048, 4.3x at the head, 9.4x at 4096x4096. Sixteen times the CUDA
threshold, for the floor's sake. `MetalGemm.MIN_MATVEC_ELEMENTS`; `Gpu.worthMatvec` still
answers with the CUDA constant (probe-free, as every `worth` does), so between `2^17` and
`2^21` an interceptor unwraps and the library declines -- the same band as the strided
tier's, and the same decision not to let `worth` consult the threshold in force.

**In situ, and the idle clock: the finding that sets the ceiling.** `MtlGemvInSitu.java`
runs the SHIPPED route (`Gpu.matvec` over `target/classes`, residency and pool included)
the way a program calls it. Back to back the medians are ~115 us at 1536x1536, ~125 at
2048x2048, ~165-205 at the head and ~295 at 4096x4096 (mins 97-106 / 105-111 / 157-184 /
250-270 over two runs, i.e. the probe's best-of plus the interceptor's own bookkeeping and
the clock's spread), and the FIRST shape a process measures pays the ramp (the head:
368 -> 244 -> 205 over three rounds in one run, 382 -> 367 -> 228 in the other, and 164
once warm). Then the same resident head with a CPU gap before every call, mean us per call:

| gap before the call | 0 | 100 us | 500 us | 1 ms | **2.5 ms** | 5 ms | 10 ms |
|---|---|---|---|---|---|---|---|
| 32000x288, resident | 347 | 351 | 355 | 367 | **792** | 858 | 973 |
| 1536x1536, resident | 213 | 194 | 193 | 197 | **488** | 528 | 631 |

**This GPU lowers its clocks once it has been idle for more than about a millisecond, and
the first command buffer after such a gap costs ~0.5 ms more.** A decode loop is exactly
that shape: one GEMV per token with the attention, RoPE and the sampler between them. So
`examples/llama2` on this machine -- `stories15M`, 256 greedy tokens, JVM class output,
three interleaved runs each -- decodes at **376 / 380 / 358 tok/s under `--simd` and
368 / 370 / 381 under `--gpu --simd`: 1.0x**, the story byte-identical across all six, and the
in-situ head (the only matrix of that model above the threshold) at 625-670 us against the
CPU's 780 (`insitu.lisp`-style measurement: 300 small GEMVs as the gap). The per-call
table above is true and the decode loop cannot collect it. There is no public API to hold
the clocks up, and keeping the device busy on purpose would be a heater; the acceptance
the item allowed for -- "if the measurement says the GEMV never pays on Metal, record the
number and keep the decline" -- is therefore half met: it pays back to back, from `2^21`,
by 2.5-9x, and it does not pay once per token, so the member is in and the guide says
both. The README's Mac line says the same in one sentence.

**Residency: measured, and kept for ONE kind of array.** The CUDA half keeps every operand
and result resident and took a fifth off its training step for it. The first build of
this round did the same on Metal -- the same `DeviceResidency` (the class `CudaResidency`
was renamed, because nothing in it is CUDA's), the same weak keys, invalidation and
two-moment drain, with a dropped entry's slab returned to the POOL rather than freed --
and measured it on `train-gpt-soseki.lisp` at the notebook's own `*n-embd*` 384 and
`*block-size*` 256 under `--gpu --simd` on the JVM class output, `(t40 - t5) / 35`, three
interleaved rounds:

| per training step, M4 Max | s/step | the 40-step run |
|---|---|---|
| before this round (pure pool) | **0.103** | 5.38 / 5.39 / 5.39 s |
| every operand and result resident, cap 1 GB | 0.106-0.108 | 5.63 / 5.65 / 5.67 s |
| ... cap 256 MB / 64 MB / 16 MB | 0.106 / 0.1055 / 0.1046 | 5.58 / 5.53 / 5.48 s |
| ... cap 0 (the bookkeeping alone) | 0.1047 | 5.44 s |
| **the GEMV's matrix only (final)** | **0.104** | 5.34 / 5.40 / 5.48 s (the pure pool the same hour: 5.30 / 5.43 / 5.46) |
| `--simd`, no device | 0.699 | 29.1 s |

Slower at every cap, by 1-5%, and the chain hits it exists for bought nothing the clock
could see. The reason is this platform's economics turned around: on unified memory the
upload residency removes is a memcpy (1.5 MB in ~75 us), while a slab held out of the
pool for a resident copy costs the pool a FRESH slab for the next call of that size class
-- and a fresh slab pays its first-touch page faults, ~1 us a page, the very measurement
that made the pool mandatory. The 1 GB cap is what CUDA measured as neutral; here it is the
worst case, because the held slabs come back only when the collector reaches their arrays,
and a training step allocates a new array per call. A smaller cap recycles sooner and
costs less, but a cap small enough to be free would evict the one array residency is FOR
on this backend -- a 36.8 MB classifier head, or a bigger model's hundreds of megabytes of
weights. So the final build keeps the cache and puts one thing in it: the matrix of an
accepted GEMV, the array that is re-read hundreds of times, written never, and cannot be
copied per call without losing (above). `x` and `y` are scratch slabs; every other member
is exactly the pure pool it was. `MetalGpuTest.onlyTheMatrixOfAnAcceptedGemvIsKeptResident`
pins the decision, and the budget / release / collection claims are pinned on GEMV
matrices (`theResidentSetIsBoundedByItsBudgetAndAReleaseGivesTheSlabsBack`,
`aCollectedHostArrayTakesItsResidentCopyWithIt`). A release gives the slabs back to the
POOL, not to the device -- `freeDeviceMemory` does not move -- which is the right shape
for a pool that is the whole point. `GpuDevice`'s `written` / `residentBytes` /
`releaseResident` / `residency` are abstract now: both halves keep copies.

**What the Mac found in the CUDA-era tests.** `GpuDeclineTest`, which "must hold on every
machine", had never run on one with Metal since the generator fill landed:
`Gpu.worthRng` read the threshold IN FORCE (`Long.MAX_VALUE` here, the fill is not a
member) from a predicate documented as driver-free -- so it was `false` at every size on a
Mac and ran the probe from an `aset`-adjacent path. It answers with the pooled constant
now, like every other `worth`. Nothing else in the suites needed a Mac to be right: the
interceptor suites' GEMV cases were already sized off `GpuThresholds.matvecMinElements()`
and switched themselves on the moment it stopped being `Long.MAX_VALUE`, at `#f` only.

**The probes.** `MtlMatvecCrossover.java` (the crossover and the accumulator question,
its own MSL with both accumulators), `MtlGemvInSitu.java` (the shipped route, back to back
and gapped; run with `-cp target/classes`), and `matvec-baseline.lisp` for the CPU column,
now with the 1448 and 1536 squares; the README records what they printed.

### Lazy results and the resident tier on Metal (2026-08-23, todo-494)

The Apple half of "A result comes home on first host touch" below, built and measured on
the same M4 Max as todo-477 (macOS 26.3.1, Oracle GraalVM 25.0.3). The CUDA round left
this backend declining the whole of it -- `lazyResults` ignored, `materialize` a no-op,
every resident-tier member `false`, no entry point in `gemm.metal` -- because todo-477 had
measured residency 1-5% SLOWER here with EAGER results and nothing about lazy ones had
been measured. Six findings, the first of them a bug that predates the item and the last of them the
decision: the mode is built, bit-identical and pinned, and the interceptors do NOT switch
it on here, because it does not pay.

**The JVM class output had not used the GPU on a Mac since todo-491.** The emitted
`_gpuInit` hands over the PTX, asks for lazy results, then hands over the MSL -- and
`Gpu.lazyResults` ran the probe. With no MSL to compile, `MetalGemm.probe` answered "the
Metal kernels are not on the classpath", the probe is once per process, and every call of
every `--gpu` class declined for its whole life -- silently, because a decline is an
ordinary outcome, and the class printed exactly what the CPU prints. The first baseline of
this round therefore measured 0.70 s a step for BOTH builds (the `--simd` figure), and the
in-situ pin that would have caught it did not exist: the JVM suite compares a compiled
program's output with the oracle's, which a dead flag also prints. `Gpu.lazyResults` no
longer probes (the wish is recorded and applied the moment the device exists), and
`JvmLinalgGpuAccelCompilerTest.theEmbeddedLibraryFindsTheDeviceThisMachineHas` loads the
embedded, renamed `RontoLispGpuGpu` out of the class's own loader and asserts it found what
the test JVM found. Every Mac figure for the class output in this file from 2026-08-23 on
is measured with that fix; the earlier ones predate the bug.

**The design is the CUDA one, over the pool.** `MetalGemm`'s members now have
`CudaGemm`'s shape -- look the operands up (`Call.lookup`), take a pool slab for what is
missing (`ensure`), upload and keep (`stage`), dispatch, record the result (`finish`) -- and
the slabs the cache holds are pool slabs: a member's result slab becomes the host array's
DIRTY entry in the shared `DeviceResidency` instead of being downloaded and recycled, an
in-place member (`scale` into itself, the Adam step, a concatenation's later slabs) marks
the slab it wrote, every operand a call uploads is kept as a CLEAN copy, and
`materialize` is a memcpy out of the slab's `contents`. The drain's flushes DOWNLOAD before
the slab goes back to the free lists (`flushNow`, also under the pool-pressure eviction in
`take`, which keeps the call's own slabs); `written` is materialize-then-drop, as on CUDA;
`lazyResults(false)` brings every dirty copy home. Eagerly -- the library's default, the
mode the tests run in -- nothing changes from todo-477: the GEMV's matrix is the only
resident array, every other slab is scratch. The two modes have one budget rule each:
eagerly `min(pool / 4, 1 GB)` for the resident set over a pool of a quarter of the
working set; lazily the POOL may hold the working set less an eighth (never less than
512 MB) and the resident set the pool less an eighth of that, because here the pool and
the resident set compete for the same slabs and the headroom is what one call's scratch
and the free lists live in (`LAZY_HEADROOM_SHARE`). The first lazy build kept the quarter
pool and fell into the trap todo-491 hit at 1 GB on CUDA: at the book's shapes the graph
a step keeps reachable until its backward is tens of gigabytes, the LRU flushed it as
fast as it was made -- 195 GB of flushes and ten whole-set evictions under pool pressure
over 13 steps -- and the step was a third slower than the pure pool. The rule above
brought the flushes to zero; it did not bring the step back, below. The pre-flight that
evicts everything a call is not holding before it would refuse the call is the same; a
dirty copy it evicts is downloaded first.

**A backend with no `double` runs binary64 in software, and that is what makes the tier
bit-identical here.** The CPU kernels behind these members compute in double and narrow
on the store. For two floats the float arithmetic IS that (the `bcast_f32` argument,
which now covers `zip`, `where`, `copy`, `abs` / `negative` / `sign` and the scalar forms
whose scalar is exactly a float -- an integer, a single-float literal, `(sqrt d-k)`). For
the rest it cannot be: a scalar that is not a float (`(linalg:mul g 0.1d0)`), every step
of the Adam update (its rule is ten doubles; the bias corrections are nothing a float
holds), and the sum fold, which `%la-fold-axis` accumulates in double. So `gemm.metal`
carries IEEE binary64 in 64-bit integer arithmetic: a value is its bit pattern in a
`ulong`, every operation unpacks to sign / exponent / 53-bit significand, works in a
128-bit integer so that every intermediate is exact or carries a sticky bit, and packs
through ONE rounding step (`f64_pack`: round to nearest even, the subnormals, overflow to
infinity) shared by add / sub / mul / div / sqrt and the exact widening of a float and the
narrowing `(float) d`. Division is restoring (55 quotient bits, the remainder sticky),
the square root digit-by-digit over a 128-bit radicand (56 root bits), the product four
32-bit partial products. It is slower than a float op by a hundred-odd instructions an
element, which a memory-bound launch over a resident operand does not notice. The pin is
`MetalGpuTest.theSoftwareBinary64RouteLandsOnJavasDoubleArithmeticBitForBit`: the scalar
forms over 2^18 bit patterns (subnormals, the specials, the tiny and the huge) and
twenty-odd scalars from 1e-310 to `Double.MAX_VALUE`, the Adam update over three steps,
the equal-shape ops -- against Java's arithmetic, bit for bit (a NaN is a NaN); the probe
that ran it first (`MtlSoftF64.java`) did the same over 1364 scalar calls with 0
mismatches. `GpuDeclineTest` still asserts no code line of the file says `double`; the
emulation is spelled `f64`.

**This GPU flushes subnormal floats to zero in every float operation, `MTLMathModeSafe`
or not.** Measured through the probe: a subnormal operand through `x * 7.0f`, `x > 0.0f`,
`fabs`, `sqrt` all answer as if `x` were zero, and a product that lands in the subnormal
range is flushed. The CPU does neither, and a bit-identity claim has to hold for those bits
too, so every float kernel guards it (`bin_op_exact`): an operand that is subnormal, or a
result below `FLT_MIN` (where the hardware has flushed it), is recomputed on the binary64
route, which works on the bits and never flushes; `abs` / `negative` / `sign` are bit
operations; the fold's amax / amin compare through an order key the flush cannot touch;
`where`'s mask test is a bit test. The guard is two compares an element. It also closes a
gap the STRIDED tier had since phase 5 -- `bcast_f32` was not bit-identical for a subnormal
operand, and nothing had fed it one. And `sqrt` needs `precise::sqrt`: plain `sqrt` under
the safe math mode is 1 ulp off in ~10% of operands (27621 of 262144 in the probe), where
`precise::sqrt` is correctly rounded and therefore `Math.sqrt` narrowed.

**The fold is a member here now -- over a resident operand only.** The round-trip refusal
of phase 5 stands and its threshold stays `Long.MAX_VALUE`: as a trip, amax / amin tie the
CPU and a float sum could not be the defun's bits. Over an operand that is already on the
device the trip is not paid and the alternative is bringing the operand home for the CPU's
fold -- `softmax`'s `amax` and `sum` over a device-resident score matrix -- so `fold_f32`
exists (the sum in software binary64, amax / amin as bit moves), and
`Gpu.worthOrResident` offers it exactly as it offers the other size-thresholded members.
`MetalGpuTest.theAxisFoldIsDeclinedForItsSizeAtEveryWidth` keeps the refusal; the tier
test pins the member.

**The floor, and where it puts the threshold.** A launch over resident operands, nothing
copied, through the shipped route (`MtlResidentFloor.java`; us per call, the CPU column a
memcpy of the operand out of the slab plus the lane loop):

| elements | device `zip` | device `scale` | CPU memcpy + loop | loop alone |
|---|---|---|---|---|
| 2^12 | 116 | 115 | 1.3 | 1.0 |
| 2^14 | 129 | 140 | 5.0 | 4.1 |
| 2^16 | 113 | 126 | 20.5 | 16.4 |
| 2^17 | 95 | 97 | 41.9 | 33.7 |
| 2^18 | 138 | 122 | 83.5 | 66.4 |
| 2^19 | 151 | 141 | ~340 | 179 |
| 2^20 | 204 | 202 | 336 | 266 |
| 2^21 | 303 | 280 | 673 | 533 |

So a resident launch is ~100-140 us whatever its size until 2^18 -- the ~77 us command
buffer and the library's own bookkeeping around it -- and crosses the CPU between 2^18 and
2^19. `MetalGemm.MIN_RESIDENT_ELEMENTS` is the floor every member is held to when it is
offered for a resident operand rather than for its size (the resident tier, and a
size-thresholded member below its threshold), and the training step put it LOWER than
that table says, at **2^14**: at the notebook's shapes the 40-step run was 5.23-5.30 s
with the floor there, 5.37-5.59 at 2^17, 5.72-5.92 at 2^18, 5.66-5.72 at 2^19, 5.78-5.91
at 2^20 (and 5.79-5.83 with no floor at all), because a declined member over a resident
operand costs a materialize, the CPU loop and the re-upload of its result around it, and
a chain that flips between the two pays both memcpys at every flip.

**What it is worth: a tie at the notebook's shapes.** `train-gpt-soseki` on the JVM
class output, `--gpu --simd`, the method of every table above, the pure-pool baseline
being this tree at the previous commit with only the probe-order fix applied, interleaved
rounds on the same hour:

| per training step, M4 Max (notebook's shapes) | s/step | the 5 / 40-step runs |
|---|---|---|
| pure pool (todo-477's final build + the probe-order fix) | **0.104** | 1.78-1.81 / 5.38-5.50 (median 5.49) |
| lazy results + the resident tier, floor 2^14 | **0.102** | 1.68-1.71 / 5.23-5.57 (median 5.27) |
| the same, operands not kept | 0.102 | 5.26-5.27 |
| the same under `-XX:+UseParallelGC -Xmn4g` | 0.100 | 5.24-5.43 (the pure pool 5.47-5.52) |

Three to four per cent, which the run-to-run spread of this program (about three per
cent) does not separate from zero -- and the output is identical between the two builds,
as it must be.

**And a loss at the book's shapes.** 6 layers, 6 heads, batch 64, `-Xmx64g
-XX:+UseParallelGC -Xmn8g`; a synthetic corpus of the novel's size and vocabulary (318315
characters, 3038 distinct) for the `(t13 - t3) / 10` runs, and the same model over a short
corpus of the same vocabulary (36456 characters) for the per-step rows, because the data
loader's setup over the full corpus costs six minutes a run and the step does not depend
on the corpus:

| per training step, M4 Max (book's shapes) | s/step |
|---|---|
| pure pool | **8.9** (`(t13 - t3) / 10`); 8.2-9.1 per step, steady |
| lazy + the tier, the quarter pool (the first build) | 12.0, with 15 GB of flushes a step |
| lazy + the tier, the pool rule above (no flushes) | 10.5-18.7 per step, mean ~15 |
| ... operands not kept | 9.0-16.7, mean ~12.7 |
| ... the tier held to 2^24 elements | 10.2-17.4, mean ~14 |
| lazy results only, the tier declined | 13.0-21.7, mean ~18 |

Read the last row first: lazy results ALONE are the worst, at twice the pure pool. With
the tier declined every CPU member materializes its operand (210 GB over 13 steps) and
its result is uploaded again by the next device member (85 GB), which is the pure pool's
traffic in both directions plus a resident set of 44 GB that the pure pool never holds.
With the tier the traffic falls (20 GB down, 36-48 GB up) and the step is still half
again the pure pool's, and it VARIES -- 10 to 19 seconds step to step where the pure pool
holds 8.2-9.1 -- with the per-call kernel times of the same kernels 1.5-2x what the 3-step
run measured for them. Three things this backend does that the GB10 does not, and
together they are the answer:

1. **Every call waits.** A Metal call is `commit` and `waitUntilCompleted`; nothing
   overlaps. On CUDA the launches are asynchronous and the host's bookkeeping, allocation
   and host-side members run under them; here the step is the CPU's time PLUS the
   device's, so a member moved from the CPU to the device pays in full and wins only if
   the device is faster at it -- and at these sizes (6-25 M elements) a memory-bound
   launch at the ~80-150 GB/s this route reaches (a 25 M-element `zip` is 2.2-3.6 ms) is
   not much faster than the M4's lane loop over the same bytes.
2. **Unified memory holds both copies.** A lazy result has a host array (zeroed, allocated,
   `.todo/492`) AND a slab; at the book's shapes that is a 58-60 GB pool of slabs beside a
   64 GB heap on a 128 GB machine, the system starts compressing pages, and the device's
   reads of shared-storage buffers slow with it -- which is what the inflated, varying
   kernel times are. The GB10's pool is device memory the heap does not share.
3. **The download it saves is a memcpy.** What todo-491 removed on CUDA -- 44 GB over the
   link in a 200-step run -- is on this platform a memcpy inside the same memory, at
   20+ GB/s; the ~1.5 GB a step that still come home here cost about 0.1 s of the 8.9.

So the acceptance rule of the item decides it: the step is not faster than the pure pool,
the number is recorded, and the decline is kept -- as a POLICY, not by tearing the mode
out. `GpuDevice.lazyResultsPay()` is the measured answer per backend (`true` on CUDA,
`false` here), `Gpu.lazyResultsIfWorthwhile()` is what both interceptors now call
(`LinalgGpuKernels.lazyResults`, `JvmGpuTemplate.gpuKernels`), and `Gpu.lazyResults(true)`
stays the unconditional request an embedder or a test makes, honoured on both backends.
`MetalGpuTest.theInterceptorsRequestLeavesResultsEagerHereAndAnEmbeddersDoesNot` pins the
decision and the distinction; the five lazy-results tests and the soft-binary64 one pin
the mode; the interpreter suite's `aDeviceResultStaysOnTheDeviceUntilTheHostFirstReadsIt`
and the hit-count half of `theResidentTierRuns...` assume `GpuThresholds.lazyResultsOn()`
and the rest of both suites run as before. Eagerly, then, this backend is exactly
todo-477's: the GEMV matrix is the only resident array, and the resident tier -- which is
offered only over a resident operand -- runs over a GEMV matrix and nothing else (a
`linalg:mul` of a resident weight by a scalar, bit-identical, result home).

**What would change the answer.** The first item above is the lever: encoding a chain
into fewer command buffers, or committing without waiting and waiting only at the first
host touch, would overlap the host with the device the way CUDA does -- and it changes
when a slab may be recycled and when an upload into a recycled slab is safe, the one
ordering the residency design exists to forbid, so it needs the same care the CUDA stream
ordering got; measure it at the notebook's shapes, where the tie is, before the book's.
The second is `.todo/492` (no host array for a lazy result), which would halve the
footprint that puts this machine under pressure. The index tier and the clip norm
(todo-493) decline here for the same reason the rest of the lazy design is off: with no
lazy results there is no download for them to save. The
probes: `MtlSoftF64.java` and `MtlResidentFloor.java`, beside the todo-477 ones.

### The JVM class carries BOTH kernel texts

`JvmGpuRuntimeBuilder` embeds the Metal classes beside the CUDA ones (`GpuDevice`,
`GpuDevice$Thresholds`, `MetalDriver`, `MetalGemm`, `MetalGemm$Probe`, `MetalGemm$Slab`)
and the MSL text beside the PTX, and `_gpuInit` hands each to its own `Gpu.useKernels` /
`Gpu.useMetalKernels`. **Both travel in every `--gpu` class whichever machine emitted it**,
because the machine that compiles a program is not the machine that runs it and a
standalone class that accelerated only on its birthplace would not be one.

The cost, measured end to end: `am.ik.gpu`'s class files are 118.4 KB (from 68.7), the
bridge 13.4, the PTX 113 and the MSL 9.4, so a `--gpu --simd` class is ~300 KB bigger than
a `--simd` one (`shaped-baseline.lisp`: 226 KB against 530). The MSL is 3% of that and the
PTX 38%; the rest is base64. If the blob ever has to shrink, the PTX's `sin`/`cos`/`tan`
argument-reduction tables are still the place.

### Tests, and the native image

| what | where |
|---|---|
| needs a METAL device | `am/ik/gpu/MetalGpuTest` |
| needs a DOUBLE-capable (CUDA) device | `am/ik/gpu/GpuTest` |
| must hold on EVERY machine, the MSL source's own shape included | `am/ik/gpu/GpuDeclineTest` |
| the interceptor, both backends, shapes sized off the threshold IN FORCE | `eval/LinalgGpuTest`, `codegen/jvm/JvmLinalgGpuAccelCompilerTest` |

`GpuDeclineTest` asserts the checked-in MSL on every machine -- it names its four kernels,
its op-code mirrors match `Gpu.MAP_*` / `Gpu.BIN_*`, and no `double` survives outside the
comments -- because that text travels in a class compiled on a Linux box, and a source
that could not compile would break only Apple users of it.

**The interceptor's suites now derive their shapes and their width from the device in
force**, through the test-scope `am.ik.gpu.GpuThresholds` shim: `SIDE` is the smallest
accepted square (64 on CUDA, 208 on Metal), `MAP_N` twice the element threshold, and
`TYPE` is `single-float` where the device has no double. A hard-coded 64 would have made
every accepted-product assertion in them vacuous on the second backend. Two of them also
stopped hard-coding a lane-kernel integer: `.kb/linalg-simd.md`'s f32 v.M probe prints
16777216 on a GB10 and 16777728 on an M4 Max, so the fallback target is now READ from an
unflagged run rather than written down -- and the `--blas` rung of that chain is compared
against `--blas --simd` rather than against the lanes, because unlike `--gpu`, `--blas`
DOES take the gemv shapes and Apple's Accelerate does not sum the way our lanes do.

Native image needs two things beyond the CUDA ones, both already in
`src/main/resources/META-INF/native-image/am.ik.rontolisp/rontolisp/`: `gemm.metal` in
`resource-config.json` (twice, under `am.ik.gpu.MetalGemm` and under the compiler's
condition), and 21 more `foreign.downcalls` shapes -- one per selector shape, plus the
two-`MTLSize`-by-value entry for `dispatchThreadgroups:threadsPerThreadgroup:`. **The type
names in that file are now the tracing agent's own** (`jlong`, `jint`, `jboolean`): the
un-prefixed aliases parse too, but `boolean` does NOT -- `Unknown value layout: boolean` --
so one spelling throughout is what keeps a re-run's diff empty.

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

The user-facing description lives in `doc/{en,ja}/guides/gpu-acceleration.md` (its own
page, split out of the `--simd` guide). Keep the
intercepted set, the size threshold, the chain order and the precision contract in sync
with it.

### The intercepted set is TWO product shapes, TWELVE element-wise members, TEN strided ones, the GENERATOR FILL, the RESIDENT and INDEX tiers -- and, outside `linalg:`, the GEMV

`linalg:dot` over two packed rank-2 operands of the same width (hence `linalg:matmul` at
rank 2 and `linalg:solve` transitively); since phase 4a `linalg::%la-matmul-nd`, the
STACKED product behind `linalg:matmul` at rank >= 3; since phase 4b the element-wise
`exp` `log` `tanh` `sin` `cos` `tan` `asin` `acos` `atan` `sinh` `cosh` `erf`; since
phase 3 the STRIDED tier -- `add` `sub` `mul` `div` `maximum` `minimum` at a BROADCAST
shape only, `sum` `amax` `amin` in their `:axis` form only, and `transpose` in its axes
form only; and since 2026-08-22 `linalg::%la-rng-fill`, the seeded generator's fill
behind `rand` / `randn` / `uniform` (below, "The second profile"). Twenty-five `linalg:`
members -- and, since later that day, ONE outside the package: `vec:matvec`, the GEMV
(below, "The GEMV, and the matrix that stays"), installed by `LinalgGpu.installVec` from
the VEC library's lazy-load hook rather than by `install`, because the two libraries
load independently and a program may reach either first.

Since 2026-08-23 the RESIDENT tier joins them -- `sqrt` `abs` `negative` `sign`, the
binary six and the five comparison masks at an EQUAL or SCALAR shape, `where`,
`%la-adam-step`, `reshape`, the rank-2 `transpose`, `%la-gather-strided`, `concatenate`,
`%la-scale` ("A result comes home on first host touch") -- and with it the INDEX tier and
the clip norm: `take-rows`, `gather`, `%la-scatter-rows`, `%la-sum-squares` ("The index
tier and the clip norm"). Every one of those is installed UNCONDITIONALLY and declines
unless an operand is already on the device, so the refusals their measurements recorded
still hold for a round trip. Forty-four `linalg:` members in all.
Nothing else is `defineFunction`ed: `#'linalg:outer`, `#'linalg:norm` and
`#'linalg:matmul` still print `#<lambda>` under the flag, and that they do is an assertion
rather than a remark (below) -- as is that `#'linalg:sub` and `#'linalg:take-rows` now do
NOT, which is each tier's own dead-flag guard.

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
- **The transcendentals** are here and are not a product at all, which is the phase-4b
  finding: measured, they are the members with the highest ratio in the whole feature.
- **The strided tier** is here at ONE call shape per member and declined at the others,
  which is the phase-3 finding: the same `linalg:sub` is a device member against a
  `(4 256 1)` operand and a decline against a `(4 256 384)` one, because `--simd` walks
  the first with an odometer and the second with lanes.
- **The generator fill** is here and is the only member with NO operand: nothing goes
  up, the draws come back, and the closed form `a^k s mod m` lets every thread jump to
  its own state -- so it is bit-identical to the sequential walk (the one member whose
  device result is byte-for-byte the CPU's at every size) and its threshold is the
  lowest of the set.

The size threshold is `Gpu.worth`'s and nothing else: below `n*m*p = 2^17` -- for a stack,
below `batch*n*m*p = 2^17`, for an element-wise map below `n = 2^14` ELEMENTS, for a
broadcast or an axes transpose below `2^15` OUTPUT elements, for an axis fold below `2^17`
INPUT elements or 256 output cells (32 over a resident operand, `.todo/493`), for a
generator fill below `2^13` elements -- the
kernel returns the null sentinel and the CPU path runs, which is why every example in
the repository is byte-identical with the flag on.

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

**todo-468 closed that half on 2026-08-21**: `linalg:erf` is intercepted now, the 21.14 s
call above is 0.155 s, and the five-step interpreter run went 332.3 -> 172.1 s under
`--simd` (329.9 -> 171.5 with `--gpu`) -- the device still buys nothing there, so the
lesson stands unchanged and only the member that dominates has moved.

### The register-tiled f32 GEMM (2026-08-22)

**Why.** The fourth profile of the training step (`.todo/491`, filed the same evening)
put `nsys` on the 200-step `train-gpt-soseki` run at the notebook's shapes, `--gpu --simd`,
JVM class output: of 1.63 s of kernel time, `gemm_batched_f32` was 1.05 s over 8610 launches
-- 5.3 ms a step, about 2.3 TFLOP/s on a device whose f32 peak is near 23 -- the classic
result of a 16x16 shared-memory tile that moves one element of each operand through shared
memory per multiply-add. cuBLAS reaches 5-7x that on the same shapes (the spike's table),
and the gap was the one device-side line the profile had.

**What was built.** `gemm_tiled<T, TM, TN>` in `gemm.cu`: a `16*TM x 16*TN` block tile,
16x16 threads, each thread owning a `TM x TN` patch at rows `ty + i*16` and columns
`tx + j*16` (so a warp's global loads and stores are contiguous and its shared reads are
conflict-free), operands staged through shared memory 16 deep in k. Two entry points,
`gemm_batched_f32_t4` (64x64) and `gemm_batched_f32_t8` (128x128), both taking the batched
parameter block at every batch size including 1. f32 ONLY: at f64 the GB10's scarce double
units pin every tile to the same speed (0.9-1.0x, and the 8x8 tile spills registers and
LOSES), so `gemm_f64` / `gemm_batched_f64` are unchanged.

**The fold is the 16x16 kernel's, bit for bit, and that is what makes the choice free.**
Each cell accumulates k ascending from +0 through one `fma.rn.f32` per term over K rounded
up to 16 with zero padding -- which is exactly what `gemm<T>` compiles to (the PTX has
sixteen `fma.rn.f32` and no `mul`/`add` in the old kernel) -- and a padded term is
`fma(0, 0, acc) = acc` (an accumulator that starts at +0 and only ever adds rounded
products is never -0). `gemm-tile-probe.cu` checked every cell of every shape below
against the old kernel: zero differences; `GpuTest.everySingleFloatProductKernelLandsOnTheSameFusedFold`
pins it against `Math.fma` over floats on the CPU at shapes that reach each tile, with
M, N and K all off the tile and off 16. So the precision contract's sentence about the
product did not change, and `CudaGemm.tileF32` may choose by speed alone.

**The rule (`CudaGemm.tileF32`), and the table it came from.** `.todo/123-gpu-acceleration/gemm-tile-probe.cu`,
GB10 (48 SMs), `nvcc -O3 -arch=native`, us per call, best of 20 after a warm-up,
`old` = the shipped 16x16 kernel:

| shape (batch x M x K . K x N) | old | 2x2 (32) | 4x4 (64) | 8x8 (128) |
|---|---|---|---|---|
| 4 x 256x384 . 384x192 (Q projection, broadcast W) | 65 | 64 | **55** | 72 |
| 4 x 256x192 . 192x256 (scores) | 45 | 45 | 39 | **39** |
| 4 x 256x256 . 256x192 (attention x V) | 45 | **43** | 44 | 50 |
| 4 x 256x384 . 384x384 (linear-o) | 124 | 115 | 76 | **72** |
| 4 x 256x384 . 384x1536 (MLP up) | 492 | 419 | 239 | **124** |
| 4 x 256x1536 . 1536x384 (MLP down) | 502 | 451 | 307 | **280** |
| 4 x 384x256 . 256x1536 (grad W up) | 479 | 430 | 219 | **134** |
| 64 x 64x512 . 512x64 (the book's attention, batch 64) | **115** | 125 | 118 | 179 |
| 64 x 64x512 . 512x2048 (the book's feed-forward) | 3470 | 2950 | **1525** | 1689 |
| 256 x 256 | **19** | 31 | 45 | 54 |
| 512 x 512 | 115 | 119 | 101 | **96** |
| 768 x 768 | 363 | 340 | 196 | **141** |
| 1024 x 1024 | 866 | 780 | 474 | **318** |
| 2048 x 2048 | 6929 | 5897 | 3109 | **1991** |
| f64, 1024 x 1024 | **4471** | 4587 | 4975 | 6761 |

Read it as three regimes. The 128 tile is 2.2-4x once its grid holds about half the SMs
(24 blocks here: `linear-o` 1.7x, MLP up 4.0x, 768-square 2.6x, 2048-square 3.5x) and
loses below that, where 48 SMs cannot be filled by 4 or 16 blocks (256-square 0.3x,
attention x V 0.9x). The 64 tile is the middle rung -- 1.2-2.3x from a full wave of blocks
up, a tie below -- and the one that takes a batch of SHORT rows: the book's batch-64 of
64-row slabs, where a 128-row tile wastes half of itself. And the 16x16 kernel keeps
everything small and everything f64. Hence: **the 128 tile when both output axes are at
least 128 and the grid has at least `SMs / 2` blocks; else the 64 tile when both are at
least 64 and the grid has at least `SMs` blocks; else 16x16** -- in SMs, so a smaller card
moves the thresholds down with it. The `#pragma unroll` the probe carried was dropped
from the shipped kernel: ptxas unrolls the k loop itself (same speed, measured), and the
pragma was 60 KB of PTX.

**What it bought, measured through rontolisp.** The guide's n x n f32 `--gpu` column,
re-measured the same evening (best of three rounds, warm; the 16x16 kernel's rows are
unchanged and were left as they were): interpreter 512 / 1024 / 2048 at 215 / 1183 /
8067 us -> **200 / 700 / 4200**; JVM class output 210 / 2233 / 8375 -> **160 / 850 /
4200**; the stacked `12 x 256` f32 380 -> 300 (the copies are most of what is left there:
9.4 MB a call). On the training step itself -- `train-gpt-soseki` at the notebook's
shapes, `--gpu --simd`, the old class against the new one, three interleaved rounds --
**nothing measurable**: 0.111 -> 0.108 s at `(t40 - t5) / 35` and 0.059 -> 0.058 at
`(t200 - t40) / 160`, both inside the run-to-run spread. The GEMM was 5.3 ms of a 60 ms
step and the tiled kernel takes about 2 ms of that; the step is the copies (next
paragraph). The kernel is still the right thing to have shipped -- it is what the guide's
tables show and what a batch-64 run of the book's own shapes would run on -- but the
example's README quotes the step, and the step did not move.

**What it did NOT buy, and why that is the finding.** The same profile put the step's
device copies at ~40% of the step and every result download at 220 MB a step; with the
GEMM 2-4x faster at its large shapes the step's slope barely moved, because the download
of a `(4 256 1536)` activation costs more than the product that made it. That is
`.todo/491`: the host-authoritative design that "Device residency, built" chose above
is now the ceiling, not the copies it removed.

### The element-wise tier (phase 4b, 2026-08-21)

Twelve members, chosen by measurement, and the measurement also REFUSED eight candidates
the same tier contains. The rule it produced is one sentence: **a member is worth a round
trip when its scalar cost is a libm CALL, and not when it is a machine instruction.**

The probes are `.todo/123-gpu-acceleration/ElementwiseCrossover.java` over
`elementwise-probe.cu` (which carries the declined candidates too, because a decline is a
measurement that has to stay re-derivable) for the device column, and
`elementwise-baseline.lisp` under `--simd` on the JVM class output for the CPU column.
GB10, us per call, one array of `n` elements:

| f64, 1.5 M elements | `--simd` CPU | device round trip | ratio |
|---|---|---|---|
| `erf` | 94550 | **760** | 124x |
| `tanh` | 14850 | **658** | 22.6x |
| `exp` | 9200 | **540** | 17x |
| `log` | 8450 | **646** | 13x |
| `sin` | 5150 | **551** | 9.3x |
| `sqrt` | **700** | 502 | 1.4x -- DECLINED |
| `add` / `mul` (two operands) | **900** | 780 | 1.15x -- DECLINED |

| f32, 1.5 M elements | `--simd` CPU | device round trip | ratio |
|---|---|---|---|
| `erf` | 95000 | **241** | 394x |
| `tanh` | 15500 | **242** | 64x |
| `exp` | 9150 | **243** | 38x |
| `log` | 8950 | **241** | 37x |
| `sin` | 5400 | **241** | 22x |
| `sqrt` | **500** | 245 | 2.0x -- DECLINED |
| `add` / `mul` (two operands) | **350** | 382 | 0.92x -- the CPU WINS |

**A CPU figure here depends on which widths the PROCESS has already run, and by ~1.3-1.9x.**
The two tables above are `elementwise-baseline.lisp`'s, which walks every size at both
widths in one process: by the time it reaches 1.5 M its `linalg:exp` call site has long
since seen `double[]` and `float[]` both, so it is bimorphic and f64 `exp` measures 9200
us. A process that measures ONE WIDTH ONLY measures **7300** for the same call -- f64 `tanh`
is 9533 measured alone against 16700 measured after the f32 pass, and f32 `tanh` 9700
against 17067. (The guide's element-wise table is four runs, one per width per flag, for
exactly this reason; this one is the fully-warmed-both-ways harness. Both are true and
they must not be mixed inside one row.) The device column does not move with order at all,
because it is copy-bound -- which is itself the tier's central fact.

**The two halves of that table are the whole design.** A transcendental is ~100 ns per
element on the CPU (`erf`) down to ~3 ns (`sin`), while the device's cost is the COPY:
540-760 us at f64 and a flat 241-245 at f32, where every member measures the same because
nothing but bandwidth is left. `sqrt`, `abs`, `negative`, `sign` and the binary
`add`/`sub`/`mul`/`div` are one instruction over one or three streams, so their CPU cost
IS bandwidth too -- and a round trip that must move the same bytes twice, over a link
slower than memory, cannot win that. At f32 it does not even draw: 350 us on the CPU
against 382 on the device, and the binary ops move three arrays where a map moves two.

**The threshold is 2^14 ELEMENTS, not 2^17 anything.** The product's threshold counts
multiply-adds; a map does one libm call per element, so the two numbers are not
comparable and the element-wise one had to be re-derived. At the threshold every member
taken is clearly ahead and every member refused is clearly behind, which is exactly where
a threshold belongs:

| f64, us/call | 4096 | 16384 (the threshold) | 65536 | 262144 |
|---|---|---|---|---|
| `exp` CPU / device | 25 / **14.5** | 120 / **20.8** | 360 / **37.1** | 1490 / **107** |
| `erf` CPU / device | 275 / **13.4** | 1010 / **24.2** | 3975 / **46.6** | 15900 / **144** |
| `sin` CPU / device | 30 / **11.8** | 55 / **21.2** | 215 / **41.9** | 860 / **107** |
| `sqrt` CPU / device | 70 / 11.0 | **5** / 18.4 | **30** / 33.5 | **115** / 95.1 |
| `add` CPU / device | **5** / 17.6 | **5** / 29.6 | **25** / 48.5 | **130** / 128 |

The cheapest member taken (`sin`) crosses over between 2000 and 4000 elements and is 2.6x
ahead at the threshold; the dearest (`erf`) is 42x ahead there. `sqrt` and `add` are
BEHIND at the threshold by 3.7x and 6x, and only reach a marginal 1.4x / 1.15x at 1.5 M --
a margin that reverses at f32 and would reverse again on a machine with a faster CPU or a
slower link. **A member that wins by less than its own measurement error is not a member.**
The unpooled threshold is `2^16`, for the same reason the product has a second one: a
~170 us floor is above the CPU's cost for 16384 elements of anything here.

**The examples are still byte-identical, and it was re-verified rather than assumed.**
`train-gpt-soseki.lisp` at its own (small) shapes prints the same bytes unflagged, under
`--simd` and under `--gpu --simd`, on the interpreter and through `-o Prog.class`: its
activations are a few hundred elements, four orders below the element threshold, so every
map declines exactly as every product does. `examples/ml/tiny-llm.lisp` likewise, its one
documented elapsed-time line aside (102 -> 111 ms interpreter, 74 -> 90 compiled: the
probe, paid once). And `CUDA_VISIBLE_DEVICES=` makes a flagged run identical to an
unflagged one on both backends -- including on the native binary, where the flag then
warns and declines.

**What it does to a transformer.** `train-gpt-soseki.lisp` at the notebook's shapes
(`*n-embd*` 384, `*block-size*` 256), JVM class output, `--simd` against `--gpu --simd`:

| | 5 steps | 20 steps | per training step |
|---|---|---|---|
| `--simd` | 11.55 s | 23.85 s | 0.82 s |
| `--gpu --simd` (phase 4a) | 8.51 s | 14.93 s | 0.43 s |
| `--gpu --simd` (phase 4b) | 8.15 s | 13.53 s | **0.359 s** |

**2.3x per step against the CPU, and 1.2x against phase 4a** -- the tier that was ~half of
what phase 4a left is now on the device, and 0.359 s is what remains. That remainder is
the answer to "what is left for phase 3": it is `softmax` / `log-softmax` / `layer-norm`
and the AdamW update, and every one of them is a CHAIN of members this tier deliberately
refuses (below).

**What `softmax`, `log-softmax` and `layer-norm` are made of, and why intercepting their
parts is not enough.** `torch:softmax` is `amax` -> `sub` -> `exp` -> `sum` -> `div`;
`log-softmax` the same with a `log`; `layer-norm` is `mean` -> `sub` -> `square` ->
`mean` -> `add` -> `sqrt` -> `div`. Exactly ONE member of each chain (the `exp`, the
`log`) is on the device, and the rest are refused members and reductions. So the device
takes one link, pays a full round trip for it, and hands the array back for the CPU to
walk four more times. Intercepting the other links would not fix that -- it would MOVE
the copies rather than remove them, and the table above says each of those links loses on
its own. **That is the phase-3 case stated as a measurement**: what these chains need is
for the array to STAY on the device between links, not for more links to be intercepted.

### The residency measurement phase 3 asked for

The same kernel, launched over buffers that are already resident, against the whole round
trip (`ElementwiseCrossover.java`'s third table). The gap is what residency would remove,
per op:

| us/call | round trip | resident | ratio |
|---|---|---|---|
| f64 `exp`, 65536 | 38.9 | 10.9 | 3.6x |
| f64 `exp`, 262144 | 108.1 | 24.7 | 4.4x |
| f64 `exp`, 1.5 M | 540.2 | 118.8 | **4.5x** |
| f64 `erf`, 1.5 M | 759.5 | 340.4 | 2.2x |
| f64 `log` / `tanh`, 1.5 M | 646.6 / 658.1 | 226.8 / 239.6 | 2.9x / 2.7x |
| f64 `sqrt`, 1.5 M | 507.2 | 79.6 | 6.4x |
| f32 `exp`, 65536 | 21.7 | 5.7 | 3.8x |
| f32 `exp`, 262144 | 52.1 | 5.8 | 9.0x |
| f32 `exp`, 1.5 M | 246.7 | 14.0 | **17.7x** |
| f32 `erf` / `tanh` / `sqrt`, 1.5 M | 242.6 / 245.5 / 243.6 | 15.5 / 13.7 / 14.2 | 15.6x / 17.9x / 17.1x |

**Three things phase 3 should take from it, and the third is the important one.**

1. **The todo's synthetic 2-4x is right at f64 and badly low at f32** -- 15-18x at the
   width `torch:` builds by default, because an f32 map is entirely copy-bound and
   residency removes the copy outright.
2. **The ratio grows with the array**, so residency pays most exactly where this flag is
   aimed: 3.8x at 65536 f32 against 17.7x at 1.5 M.
3. **A resident chain would also make the REFUSED members worth taking.** `sqrt` resident
   at 1.5 M f64 is 79.6 us against 700 on the CPU, and `add` is bandwidth on both sides
   with no copy left to pay -- so the eight declines above are declines of the ROUND
   TRIP, not of the device. Phase 3 does not merely speed up the twelve members here; it
   changes which members exist. That is the strongest argument in this file for doing it.

**And phase 3 then measured the other half of that argument, which is what settles it**
(the section after next). This table is a per-OP ceiling; what decides is the share of a
real program those ops' copies are, and on `train-gpt-soseki.lisp` under `--gpu --simd`
they are 1.5% of a training step. Point 3 also turned out to be answerable without
residency at all for the members that mattered: it is the CPU column of a BROADCAST
`add`/`sub` that phase 4b never measured, not the copies, that decides those.

### The strided tier, and why residency was NOT built (phase 3, 2026-08-21)

Phase 3 was written as "device residency": keep an array on the device across a chain of
accelerated calls so the copies are paid once for the chain. It was not built, and the
reason is a measurement rather than a difficulty. **The premise 4b handed it is wrong in
the one way that matters**, and correcting it produced a different member set instead --
six binary ops at a BROADCAST shape, three axis folds and the axes transpose, all of them
bit-identical to the defun, all of them 3-8x on a plain round trip with no cache anywhere.

**The premise, and what refutes it.** 4b's case for residency was that `softmax`
(`amax`->`sub`->`exp`->`sum`->`div`) and `layer-norm` are chains in which "exactly one
link is a device member and every other link is a member 4b measured and REFUSED", so
intercepting more links would MOVE copies rather than remove them. The refusal is real
but it is a refusal of a different call: **4b measured `add`/`sub`/`mul`/`div` at EQUAL
shapes, where `--simd` runs a lane loop**. Every one of those links in a real `softmax`
or `layer-norm` is a BROADCAST -- `(4 256 256) - (4 256 1)`, an array against its own row
reduction -- and `.kb/linalg-simd.md` says in as many words that the broadcast path is a
SCALAR ODOMETER walk in every `--simd` backend, "no lanes". The same is true of the axis
folds and of `transpose` with an axes list. So the CPU column 4b measured is not the CPU
column these calls take, and the crossover is nowhere near where 4b put it.

Measured on the GB10, us per call, JVM class output, `--simd` against `--gpu --simd`, at
the shapes `train-gpt-soseki.lisp` produces at the notebook's own (batch 4, block 256,
n-embd 384, 2 heads). CPU column `.todo/123-gpu-acceleration/shaped-baseline.lisp`,
device column the same file under `--gpu --simd`:

| f32, us/call | `--simd` | `--gpu --simd` | |
|---|---|---|---|
| `sub` (4 256 256) - (4 256 1) | 442.5 | **87.5** | 5.1x |
| `sub` (4 256 384) - (4 256 1) | 660.0 | **117.5** | 5.6x |
| `mul` (4 256 384) * (384) | 665.0 | **115.0** | 5.8x |
| `sum :axis 2` (4 256 256) | 202.5 | **75.0** | 2.7x |
| `sum :axis 0` (4 256 384) | 297.5 | **70.0** | 4.3x |
| `mean :axis 2` (4 256 384) | 320.0 | **97.5** | 3.3x |
| `var :axis 2` (4 256 384) | 1387.5 | **475.0** | 2.9x |
| `transpose '(0 2 1)` (4 256 192) | 335.0 | **75.0** | 4.5x |
| `softmax :axis -1` (4 256 256) | 1915.0 | **402.5** | 4.8x |
| `log-softmax :axis -1` (4 256 256) | 1920.0 | **407.5** | 4.7x |
| `amax :axis 2` (4 256 256) | 92.5 | **75.0** | 1.2x -- the weakest taken |
| `sub` (4 256 384) - (4 256 384), SAME shape | 85.0 | 87.5 | DECLINED: both columns are the CPU |

| f64, us/call | `--simd` | `--gpu --simd` | |
|---|---|---|---|
| `sub` (4 256 256) - (4 256 1) | 452.5 | **130.0** | 3.5x |
| `sub` (4 256 384) - (4 256 1) | 672.5 | **180.0** | 3.7x |
| `sum :axis 0` (4 256 384) | 277.5 | **112.5** | 2.5x |
| `transpose '(0 2 1)` (4 256 192) | 340.0 | **110.0** | 3.1x |
| `softmax :axis -1` (4 256 256) | 1805.0 | **595.0** | 3.0x |
| `var :axis 2` (4 256 384) | 1262.5 | **747.5** | 1.7x |
| `sub` (4 256 384) - (4 256 384), SAME shape | 172.5 | 150.0 | DECLINED: both columns are the CPU |

**The last row of each table is the whole reason this is not a reversal of 4b**, and it
reads differently from the others on purpose: the tier declines it, so the two columns are
the same call and the pair measures nothing but the harness. The number that refuses it is
`StridedCrossover.java`'s raw round trip for the SAME shape -- **112.3 us at f32 against
85.0 on the CPU, and 184.0 at f64 against 172.5** -- which is 4b's refusal re-measured
through this tier's own kernel and reaching the same answer.

`softmax` and `log-softmax` are not members and never touch the device directly; they are `amax` -> `sub` -> `exp` -> `sum` -> `div` and every link of that is
now one, so they are accelerated TRANSITIVELY -- five round trips where residency would
have made one, and still 4.7x.

#### The members, the kernels and the thresholds

Three shapes, two kernel families and six entry points (`bcast_f64/f32`,
`gather_f64/f32`, `fold_f64/f32`), taking their member as an op-code PARAMETER exactly as
`map` does:

- **`add` `sub` `mul` `div` `maximum` `minimum` at a BROADCAST shape** (`Gpu.bcast`,
  op codes `Gpu.BIN_*`). Each operand carries one stride per OUTPUT axis, 0 on an axis it
  is stretched across -- `%la-bcast-strides` verbatim, so the device reproduces
  `%la-bcast-loop`'s odometer with an integer division per axis instead of a carry. An
  EQUAL-shaped pair is declined by the interceptor before the library is asked.
- **`sum` `amax` `amin` with `:axis`** (`Gpu.fold`, op codes `Gpu.FOLD_*`). Any rank
  reduces to `outer x len x inner`, which is what `%la-fold-axis` already computes. One
  thread per OUTPUT cell, walking its axis SEQUENTIALLY and ascending -- deliberately not
  a tree reduction, because a tree reduction would not be the defun's sum. `mean`, `var`
  and `std` ride on it transitively, as they do on the CPU.
- **`transpose` with an axes list** (`Gpu.gather`): a permuted copy, one source stride per
  output axis. The plain rank-2 form is NOT a member -- that one has a `--simd` lane form
  (a shuffle butterfly), which is the same distinction the tier is built on.

**These are BIT-IDENTICAL to the scalar defun at both widths, and that is a new thing for
this flag.** The kernels read every element widened to `double`, compute in `double` and
narrow only on the store, which is `%la-bcast-loop`'s and `%la-fold-axis`'s own rule; the
four arithmetic ops and the two strict selects are correctly rounded in IEEE 754 and a
copy is a copy, so there is no libm anywhere in the tier to disagree about. **Residency
would not have changed this either way, but it is worth stating plainly: the element-wise
tier's precision break did NOT widen, and the members added here can be checked by
byte-identity rather than by tolerance.** `LinalgGpuDeclineTest.theStridedTierIsByte
IdenticalWithTheFlagOnEveryMachine` and its compiled twin assert exactly that, over
inexact data, above the thresholds.

**Two thresholds, both re-derived rather than inherited.** A broadcast or a gather
declines below **2^15 = 32768 OUTPUT elements**; an axis fold below **2^17 = 131072 INPUT
elements**, and additionally below **256 output cells** whatever its input size -- a fold
with one output cell is a single-threaded device loop and loses to any CPU, which is
exactly what a whole-array `(linalg:sum a)` or a keepdims-less vector reduction is. The
unpooled floors are 2^17 and 2^19. Over a RESIDENT operand the cell floor is one WARP
instead (32; `FOLD_RESIDENT_MIN_CELLS`, 2026-08-23), because there the CPU alternative is
not a free walk over the operand but a DOWNLOAD of it -- see "The index tier and the clip
norm", which is also where that floor's own measurement is. The sweep both columns come from
(`shaped-baseline.lisp` and `StridedCrossover.java`, same shapes row for row):

| n | CPU bcast f64 / f32 | dev bcast f64 / f32 | CPU fold f64 / f32 | dev fold f64 / f32 | CPU transpose f64 / f32 | dev transpose f64 / f32 |
|---|---|---|---|---|---|---|
| 4096 | **7.0 / 6.5** | 20.1 / 17.8 | **2.0 / 2.0** | 12.5 / 12.9 | **6.5 / 6.0** | 14.6 / 13.6 |
| 16384 | 27.0 / 25.5 | **21.8 / 21.1** | **8.5 / 9.5** | 27.4 / 21.5 | 30.0 / 28.0 | **20.3 / 16.3** |
| 32768 | 53.0 / 50.5 | **25.7 / 22.2** | **16.5 / 18.5** | 26.7 / 27.1 | 59.5 / 56.5 | **25.2 / 21.1** |
| 65536 | 104.5 / 101 | **36.0 / 27.2** | 33.5 / 37.0 | 30.6 / 29.4 | 118.5 / 114 | **34.6 / 25.6** |
| 131072 | 210 / 200 | **54.9 / 37.5** | 70.0 / 70.0 | **40.8 / 33.5** | 235 / 225 | **55.2 / 36.3** |
| 262144 | 420 / 400 | **90.5 / 59.0** | 140 / 145 | **61.2 / 43.1** | 470 / 450 | **93.2 / 59.0** |
| 1048576 | 1735 / 1615 | **340 / 179** | 575 / 585 | **166 / 101** | 2200 / 1830 | **339 / 182** |

The device column here is the RAW round trip (`StridedCrossover.java`, one kernel over
buffers it allocates and frees itself); the shaped table above is the whole rontolisp call
path, which at these sizes adds a further 10-40 us of header copy and wrapper. Compare a
row with a row and never a table with a table.

The broadcast and the gather cross over between 4096 and 16384 at both widths; at 16384
the f64 margin is 1.2x, which is inside the measurement, so the threshold sits at 32768
where it is 2.1x -- the same "where the win is unambiguous, not where it first appears"
rule the product's threshold follows. The fold is level at 65536 and 1.7-2.1x at 131072,
which is where its own threshold sits. **`amax`/`amin` with an axis are the weakest
members in the whole feature** (1.2-1.4x at the transformer's shape, because the CPU's
`amax` fold is ~25% cheaper than its `sum` fold); they are taken because the fold kernel
is the same one and because `softmax`'s first link is an `amax`, and the number is
recorded here so a future measurement can drop them without re-deriving it.

**A DECLINED strided call must allocate nothing, and that is an ORDERING rule inside the
interceptor.** Unlike the product and the map, whose members are rare in an ordinary
program, this tier sits on `linalg:add` / `sub` / `mul` / `div` -- call sites a program
runs constantly and which mostly decline. So the size test comes FIRST, over a bound that
costs nothing (a broadcast output is at least as big as either operand; a transpose's
output is the operand's own element count), ahead of the broadcast-shape derivation and
the permutation check, both of which allocate an `int[]` the decline would throw away.
The first draft did it the other way round and its declined path allocated two arrays per
call. Keep the cheap bound first.

**The layout rides in a fourth device buffer.** A broadcast needs the output dims plus one
stride per axis per operand -- `3 * rank` ints, at most 192 bytes -- and a gather two
thirds of that. It is one more pooled allocation (0.7-2.3 us) and one more tiny copy per
call, and it goes through the same pre-flight and the same `finally` as the operands, so
`aRunOfStridedCallsFreesEveryBufferItAllocates` is the leak pin for the FOUR-buffer path
that neither the product's nor the map's leak test reaches. Passing the layout as a
by-value kernel parameter would save the allocation and cost a second parameter-packing
shape; at these sizes that is not a trade worth making.

**The JVM backend gained one genuinely new thing: a device rung at the EXTENDED call
sites.** Until now `.kb/linalg-simd.md`'s option-form machinery
(`LinalgKernelCallLayout`, `laSumAxis` and friends) was `--simd`-only, and `.kb/gpu.md`
said so. The axis folds and the axes transpose have NO base-shape kernel on the device, so
`JvmLinalgKernelCompiler` now claims the option form when EITHER bridge has a kernel for
it and emits the device attempt with the SAME layout the lane attempt uses -- the two
kernels take the same parameters in the same order, so no second table sits between them.
`JvmLinalgGpu.kernelKey` answers `null` for a member with no base kernel and
`extendedKernelKey` for the option form; a call shape at which nothing would be attempted
routes to `compileDefault`, which is what a `--gpu`-only build reaching `(linalg:sum a)`
does. Pinned by `anOptionFormArgumentIsEvaluatedExactlyOnceEvenWhenTheDeviceDeclines`,
because a chain of any length is only safe if the temps are read rather than recompiled.

**The blob grew and the class with it.** `am.ik.gpu`'s class files are 68.7 KB (from
55.7), the bridge 13.3 KB (from 7.3) and the PTX 113 KB (from 86.9): base64 and the
verbatim PTX come to ~222 KB against phase 4b's 171. Measured end to end on
`train-gpt-soseki.lisp`, a `--simd` class is 417 KB and a `--gpu --simd` class 627 KB
(from 589). The strided kernels are 28.8 KB of PTX for six entry points, which is cheap
per member next to `sin`/`cos`/`tan`'s 38 KB for three.

#### What a training step is actually made of, and why residency cannot pay

The end-to-end number first, and it is the smallest in this file.
`train-gpt-soseki.lisp` at the notebook's shapes, JVM class output, per training step
from a 5-step and a 40-step run (best of seven interleaved runs each; the medians are in
brackets, and the spread is why both are quoted):

| | per training step |
|---|---|
| `--simd` | 0.89 s [0.93] |
| `--gpu --simd`, phase 4b | 0.25 s [0.29] |
| `--gpu --simd`, with the strided tier | **0.21 s** [0.27] |

**3.4-4.3x against the CPU, and 1.1-1.2x against phase 4b** -- against 3-6x for every
member the tier took. The gap between those two numbers is the finding, and it is the one
phase 3 should be remembered for.

**A JFR execution profile of the `--gpu --simd` step says where the time goes, and it is
not `linalg:` any more.** Top frames over a 40-step run, 1159 samples, phase 4b build
(`jfr print --events ExecutionSample --stack-depth 1`; the compiled Lisp functions carry
no line-number table, so a filter on `line:` sees only the Java half -- an earlier draft
of this section was measured that way and reported the CPU kernels as 63% of the step
when they are 11%):

| frame | samples | what it is |
|---|---|---|
| `TORCH::%O-ADAM-STEP` | 356 (31%) | the AdamW update, a per-element BOXED Lisp loop |
| `_dbl` | 167 (14%) | boxing a double -- mostly that loop's and the RNG's |
| `_fvAset1` | 91 (8%) | `(setf (row-major-aref ...))`, same loop |
| `LINALG:RAND` / `RANDN` / `%LA-RNG-NEXT` | 159 (14%) | the dropout masks, boxed RNG loops |
| `laBcastFF` + `laFoldAxis` + `laTransposeAxes` | 124 (11%) | the three kernels this tier took |
| `%LA-GATHER-STRIDED` | 56 (5%) | slicing and indexing |
| `memcpyHtoD` + `memcpyDtoH` | 17 (1.5%) | every device copy in the step |

After the tier: the three kernels fall to 9 samples of 1042 and the copies rise to 37.
**So residency's whole ceiling on this program -- removing every device copy there is --
is 3.5% of a step**, and phase 4b's own residency table (2.2-6.4x at f64, 15.6-17.9x at
f32 per op) is measuring an op whose copies are 1.5% of the program that op runs in. That
is the answer to "does residency pay": **not here, not by a factor that could survive the
15% run-to-run spread of the same program on this machine.**

What actually dominated was `torch::%o-adam-step`: PyTorch's Adam rule written as a
`do` loop over `row-major-aref` / `(setf (row-major-aref ...))` per element per parameter,
which boxes a double per element and was on NO acceleration seam -- not `--simd`, not
`--blas`, not `--gpu`. Second was `linalg:rand` / `linalg:randn`, the dropout masks, which
are the same shape of loop. **Between them they were about half of what a `--gpu --simd`
training step cost, and neither was a `linalg:` member.** That was a `torch:`-level item
and not this one -- todo-473, filed off this profile -- and it is recorded here because it
is the reason no further work on THIS seam will move this program much.

**todo-473 closed it (2026-08-22) by making both of them `linalg:` members**: the Adam
element loop became `linalg::%la-adam-step` and the three generator fills became one
`linalg::%la-rng-fill`, each with a kernel on all three `--simd` backends
(`.kb/linalg-simd.md`). Re-profiled on this box with the strided tier in, same program,
same 40 steps, `--gpu --simd`, before and after that change (the sample TOTALS are what
moved -- 1514 samples before, 590 after, for the same work):

| frame | before | after |
|---|---|---|
| `TORCH::%O-ADAM-STEP` / `RontoLispSimdBridge.laAdamStep` | 339 (22%) | **16 (3%)** |
| `LINALG:RAND` + `RANDN` + `%LA-RNG-NEXT` / `laRngFill` | 242 (16%) | **52 (9%)** |
| `_fvAset1` | 221 (15%) | 24 (4%) |
| `_dbl` | 150 (10%) | 28 (5%) |
| `%LA-GATHER-STRIDED` | 118 (8%) | 111 (19%) |
| `memcpyHtoD` + `memcpyDtoH` | 138 (9%) | 139 (24%) |

The per-step wall clock over three interleaved rounds (medians; the same
`(t40 - t5) / 35` this section's table uses):

| per training step | before todo-473 | after |
|---|---|---|
| `--gpu --simd` | 0.326 s | **0.149 s** |
| `--simd` | 0.872 s | 0.834 s |

**2.2x on the device build**, against 1.05x on the CPU-only one -- and that gap is the
finding to carry forward, because it is this section's own argument turned around: once
`linalg:` is on the GPU, everything that is NOT `linalg:` is the program. The 5-step run
(setup-dominated: every weight matrix is a `linalg:randn`) moved 6.8 -> 3.0 s on the device
build and 9.5 -> 5.8 s on the CPU one, which is the generator alone.

**And it moves the residency answer, in residency's favour but not far enough.** The device
copies were 9% of a step and are now 24% of a much shorter one; the ceiling residency could
remove is still only the host-to-device half of that (below), and re-deriving it is still
the first thing to do before building any of it.

#### The second profile (2026-08-22), and the round it drove

The same program, the same 40 steps, JVM class output, `--gpu --simd`, profiled AGAIN
after todo-473 -- and the finding was that what the first profile had called "not
`linalg:`" was mostly `linalg:` members that were not INTERCEPTED: boxed odometer walks
the interpreter and both compilers still ran as the defun. Top frames, 600 samples:

| frame | samples | what it is |
|---|---|---|
| `%LA-GATHER-STRIDED` + `%LA-BROADCAST-TO` + `WHERE` + the `_dbl` / `_add` they drive | ~130 (22%) | `torch:masked-fill`: `linalg:where` over the causal mask, which MATERIALIZES three broadcast copies through the strided gather and then selects, forward and backward |
| `memcpyHtoD` + `memcpyDtoH` | 133 (22%) | every device copy in the step |
| `laRngFill` | 47 (8%) | the dropout masks -- already a kernel, still sequential |
| `EMAP` through `GREATER` | ~38 (6%) | the dropout mask's compare, `%la-bcast`'s `emap` branch |
| `_lambda_576` (`torch:index-select`'s backward) | ~30 (5%) | the embedding scatter-add, inline in `torch.lisp` |
| `CLIP-GRAD-NORM` | ~24 (4%) | two boxed loops |
| `SLICE` (`torch:cat`'s backward) | 20 (3%) | the strided gather again |
| `laEwFS` + `FloatVector.intoArray` | ~45 (8%) | the f32-array-times-double-scalar loops, scalar by the precision contract |

**Three things were done, in the order the table ranks them.**

1. **Eleven members went onto the `--simd` seam** (`.kb/linalg-simd.md`, "The selects and
   copies"): `where`, the five comparison masks, `take-rows`, `%la-gather-strided` (slice
   and broadcast-to), and three new internal members for loops that lived in `torch.lisp`
   -- `%la-scatter-rows` (index-select's adjoint), `%la-sum-squares` / `%la-scale`
   (clip-grad-norm). None is arithmetic, so all are bit-identical and no precision
   decision was needed; none is a DEVICE member, because a de-boxed CPU select over a
   1 MB mask is ~0.3 ms where the device round trip would be ~0.12, and four of them a
   step is not worth a tier. After it the frames above read 6 + 4 + 0 + 0 + 0.
2. **The generator fill became a device member** -- the one the table says is the next
   cost and the one that had to be bit-identical or nothing (`linalg:seed`'s promise).
   The closed form is what makes it possible: thread `i` computes `s_i = a^(i*draws) s
   mod m` for each of the three LCGs by square-and-multiply (exact integers), then draws
   exactly as the walk does -- the same divides, the same left-associated sum, the same
   frac-by-compares -- every arithmetic step a `_rn` intrinsic so nvcc cannot contract
   the `lo + span * u` of `uniform` into an FMA, and `Gpu.rngAdvance` advances the END
   state on the host by the same closed form. Asserted bit-for-bit at both widths and
   all three rules (`GpuTest.theGeneratorFillIsBitIdentical...`, the interpreter and JVM
   suites on `seed` + `rand`/`randn`/`uniform`/`choice`/`%la-rng-next` in one program).
   The threshold is `2^13` elements (`RngCrossover.java`: one uniform draw per element is
   0.7-0.8x at 2^12 and 1.6-1.8x at 2^13, the normal 4x at 2^12, 20-45x at 10^6); on
   Metal it is `Long.MAX_VALUE` -- the member needs a double. One buffer, no copy up, a
   `download`: the cheapest round trip in the file. The dropout `rand` at `(4 256 384)`
   went from ~4 ms to ~50 us, and the 5-step (setup-dominated, every weight a `randn`)
   run from 3.3 s to 1.9 s.
3. **`cuMemGetInfo` was amortized** (above, "A DECLINE MUST COST THE DEVICE NOTHING").

**And the copy route was measured again and KEPT.** The GB10 answers
`CU_DEVICE_ATTRIBUTE_INTEGRATED` 1, `PAGEABLE_MEMORY_ACCESS` 1,
`PAGEABLE_MEMORY_ACCESS_USES_HOST_PAGE_TABLES` 1: a kernel can read host memory directly,
and `ZeroCopyRoute.java` measured what that is worth for an f32 `exp` map (us/call, best
of many):

| elements | R0 today (critical copies) | R1 pinned staging + DMA | R2 zero-copy + Java copies | R3 kernel over host memory, no copies | R4 kernel over device memory |
|---|---|---|---|---|---|
| 65536 | 27.2 | 31.2 | 22.5 | 8.6 | 6.4 |
| 262144 | 52.2 | 93.7 | 61.5 | 15.2 | 6.7 |
| 1 M | 165.7 | 393.7 | 272.5 | 40.7 | 11.8 |
| 4 M | 667.5 | 1732.2 | 1212.3 | 144.7 | 137.5 |

R3 is the prize -- 4x on every op at 1 M -- and it is unreachable: a kernel reading the
Java heap must have the array PINNED for its whole run, and FFM's `critical` pins for one
downcall only (a launch returns before the kernel reads; a `cuMemcpyDtoH` after it pins
the DESTINATION only; the address of a heap array is not even obtainable outside a
downcall), so the only safe zero-copy is R2, through pinned host buffers -- and the Java
`MemorySegment.copy` into them runs at 35-60 GB/s single-threaded, slower than the
driver's own pageable copy (R0's ~53 GB/s), so R2 loses past 262144 and wins 17% at
65536. Neither pays for a pinned pool and its budget. **The route stays R0**, and any
future change to it re-runs that table first.

After the three, the profile reads (270 samples): `memcpyHtoD` + `memcpyDtoH` 111 (41%),
`laEwFS` + `intoArray` + `laEwFF` 45 (17%), `laAdamStep` 22 (8%), `laWhere` 6,
`laGatherStrided` 4, the clip-grad-norm frames gone. Per training step, JVM class output,
the same `(t40 - t5) / 35` as the tables above, five interleaved rounds, MEDIAN [range]
-- and note the range: the device build is bimodal run to run on this machine (a
40-step run is 5.3 s or 6.9 s and rarely in between), which is wider than the 15% the
earlier sections quote, so read the ratio:

| per training step | before this round (2 rounds) | after (5 rounds) |
|---|---|---|
| `--gpu --simd` | 0.148 s [0.135-0.160] | **0.119 s** [0.097-0.144] |
| `--simd` | 0.826 s | 0.797 s [0.797-0.811] |
| the 5-step run (setup: every weight a `randn`) | 3.3 s | **1.9 s** |
| the 40-step run, whole | 8.1-8.9 s | 5.3-6.9 s |

6.7x against the CPU build at the medians, 8x at the best pair; 0.21 -> 0.149 -> 0.119 s
across the three rounds of this file. **The residency ceiling is therefore re-derived at
roughly one fifth to one quarter of a step** -- the host-to-device half of the 41% --
and that number, the design below and the writer enumeration (one entry longer than the
design's: `%la-scatter-rows`, `%la-scale` and `%la-adam-step` write in place) became
`.todo/474`, which the next round built.

#### The residency design that was weighed, and the enumeration it would need

Recorded on 2026-08-21 so that whoever revisited it would start from here rather than
from todo-123's two sentences; the next section is what was then built from it on
2026-08-22, and where the design had to change.

**Where the handle would live.** The todo offers an identity-keyed device cache or a
device handle inside the packed array. **The handle cannot go in the array**: on the
interpreter a packed array is a `LispDoubleFloatArray(double[] data, int[] dims)` record
and a field could be added, but on the JVM class output the array IS a bare `double[]`
with a `[rank, dim..., data...]` header inside it and there is nowhere to put one. So the
only mechanism that works on both backends is a cache keyed on the IDENTITY of the
primitive `double[]`/`float[]` -- which exists on both, and is the same object the
interceptors already unwrap. That is the shape to build if it is ever built, and the two
backends would NOT need different mechanisms.

**What makes it a cache and not an ownership transfer.** A resident buffer whose host
array is not also written is only sound if every host READ can be intercepted, and on the
JVM class output an element read is a raw `daload` -- there is no seam. So the host array
must stay authoritative, the device copy is a cache of it, and residency removes the
HOST-TO-DEVICE half of the round trip only. That halves the ceiling above again.

**The invalidation rule it would need, and the write paths it must cover.** Every
in-place write to a packed float array, enumerated:

- the interpreter: `Environment`'s `aset` / `row-major-aset` (`LispFloatArray.setElement`,
  `Environment.java:1096` and `:1102`) and `replace` (`:5867`). Those three are the whole
  set -- every other producer allocates.
- the JVM class output: `_fvAset1` / `_fvAset2` / `_fvAsetN` (`JvmFloatArrayRuntimeBuilder`),
  reached by `(setf (aref ...))` and `(setf (row-major-aref ...))`, plus the general
  `_aset*` chain they delegate from.
- `linalg::%la-make` fills a FRESH array through those same setters, so it needs no rule
  of its own: an array the cache has never seen cannot be stale.
- `vec:`'s `-into` siblings (`vec:add-into` and friends, `VecSimdKernels`' destination-
  passing kernels) write into a caller-supplied packed array and WOULD need the hook;
  `linalg:` has no `-into` member.
- `torch:set-data` REBINDS a tensor's data field rather than writing into the old array,
  so it invalidates nothing; the optimizers are the opposite case and are the reason to
  check -- `torch::%o-adam-step` writes the parameter, the two moments and nothing else
  through `(setf (row-major-aref ...))`, i.e. through the enumerated setters.
- **there are no aliasing views to worry about**: `make-array :displaced-to` requires a
  general (boxed) array and rejects a packed one outright (`Environment.requireArray`),
  so no second object can write another's storage. That is what makes IDENTITY
  invalidation sound rather than merely likely.

The rule would then be: **a device copy is valid only while its host array has not been
written, and every enumerated setter drops the entry for the array it writes** -- a
reference compare against a small resident set, so an `aset`-heavy program pays a few
nanoseconds. And the buffers would need a release policy (an LRU against a byte budget
read from `cuMemGetInfo`, since a training run that never releases is an OOM), pinned the
way `GpuTest`'s leak tests are.

None of that is hard. It is simply not worth 3.5% of a step, and it is worth re-deriving
before anyone spends the complexity: re-run `ElementwiseCrossover.java`'s third table for
the per-op ceiling and the JFR profile above for the share of the program that ceiling
applies to. **The first number is the one todo-123 quotes and the second is the one that
decides.**


#### Device residency, built (2026-08-22, todo-474)

The design above, built -- and the record of the two versions that were measured and
thrown away on the way, because what decides whether a cache of device copies pays on
this machine turned out not to be the cache.

**What it is.** `am.ik.gpu.CudaResidency` (today `DeviceResidency`, unchanged): a map from a host array -- the primitive
`double[]` / `float[]`, by IDENTITY, exactly as the design said, since that is the one
object both interceptors already unwrap -- to a device buffer holding a copy of its
elements, with the span it mirrors (`offset`, `bytes`; a different span is a miss). Every
member of `CudaGemm` now looks each operand up before it allocates (a hit is the launch
pointer and no upload), records an uploaded operand after its upload and the result after
its download (device and host hold the same bytes at both moments), and frees what the
cache dropped at the two moments a stream-ordered free is safe to enqueue: the start of a
call, before any operand of it is looked up, and the end of one, after the launch and the
synchronous download. A free enqueued BETWEEN an operand's lookup and its launch would be
ordered ahead of the kernel that reads it. The host array stays authoritative, so
residency removes the host-to-device half of the round trip only, as the design said it
would. `Gpu.written(host)` drops an entry and queues its buffer without a driver call --
it runs on whichever thread wrote the array, and needs no context. `GpuDevice` gained
`written` / `residentBytes` / `releaseResident` (empty defaults then; abstract since
todo-477, when the Metal half took the same class -- renamed `DeviceResidency` -- for its
GEMV matrices, "Residency and the GEMV on this backend" above). The class files travel in
the JVM blob (`DeviceResidency`, its `Entry`, `Key` and `Lookup`) and the blob test pins
the list.

**The keys are WEAK, and that was the first correction.** The first build held its keys
strongly and let the LRU decide; on `train-gpt-soseki.lisp` under `--gpu --simd` it halved
the uploads and made the step **2.3x slower** (0.32 s against 0.13 at steps 5-40). Every
activation and gradient the step allocates stayed reachable from the cache, the Java heap
grew to 14 GB, the driver's pool grew with it one cold allocation at a time, and the
device-to-host copies went from a quarter of the step to two thirds. A cache keyed on an
array's identity has no meaning once the array is unreachable, so the key is now a
`WeakReference` with an identity hash, a collected key turns up on a `ReferenceQueue`, and
the next drain frees its buffer. `LinkedHashMap` in access order over those keys is the
LRU; a lookup presents a transient `Lookup` whose `equals` matches a stored key by
referent, so a lookup allocates no reference.

**The budget is a CAP on the pool, not a share of the card, and that was the second.**
With weak keys the step was still slower than without residency (T40 7.5-10 s against
5.2-6.8 before; T200 17-19 s against 15.5-16.5), with half the uploads. nsys said why:
`cuMemcpyDtoH` had gone from 533 ms to 2550 ms over a 40-step run for the same 7904 calls
and the same bytes, and `cuMemAllocAsync` from 1.9 to 8.6 us a call. Two probes separated
the two:

- `ResidencyCost.java`: live stream-ordered blocks do NOT slow a copy. One 1 MB alloc +
  HtoD + DtoH + free cycle is 50 us with nothing else alive, with 64, 512 or 2048 live
  1 MB blocks the pool handed out earlier, and after freeing them; the growing pattern
  (alloc and keep, 300 times) reads 24 us a DtoH throughout. Raising the pool's release
  threshold (`CU_MEMPOOL_ATTR_RELEASE_THRESHOLD`, driver default 0 = hand every unused
  reserved byte back at every sync) changes the reserve it keeps (2080 MB against 32)
  and nothing else. It is set to the maximum at probe time anyway -- a pool that holds a
  training step's copies rather than three buffers should not be unmapped around every
  synchronous copy -- and a failed allocation still trims it explicitly, so a decline
  still costs the device nothing.
- `FreshPageCost.java`, the decisive one: **a device copy to or from a host array the GPU
  has never touched costs ~9 us per 4 KB page** -- an HtoD from a fresh 1 MB Java array
  is 2.3 ms median against 25 us over a reused one, a DtoH into one is 50 us median with a
  fifth of them over 2 ms, and with eden churn the median recovers but the p90 does not.
  The GB10 answers `PAGEABLE_MEMORY_ACCESS_USES_HOST_PAGE_TABLES` 1: the copy engine
  reaches pageable memory through the CPU's page tables, and the first translation of
  each page is the cost. What warms a page is a CPU copy INTO it (a `System.arraycopy`
  into the fresh array first: DtoH 35 us median, 70 us max) -- not the JVM's own zeroing
  of it, and not a store per 4 KB page (THP) -- or any device access to it, in either
  direction.

That second probe explains the whole history of this section. A training step's result
arrays are fresh, so every download lands on cold pages -- and the BASELINE had been
paying for that too (65% of its upload time and half its download time were in under 1%
of the copies), just less: its 15,000 uploads a run touched most of the eden, so the
arrays it later downloaded into were usually pages the GPU had seen. Residency halved the
uploads and took the incidental warming with them, and the downloads went cold: 619 of
7904 over a millisecond, 93% of the download time. So:

- **Every download is staged** through one pinned 16 MB bounce buffer
  (`CudaGemm.BOUNCE_BYTES`; `cuMemHostAlloc` at probe time so every leak test's baseline
  includes it; a non-critical `cuMemcpyDtoH` into it and `MemorySegment.copy` into the
  result, 16 MB at a time, under one lock). The DMA into pinned memory is never cold, the
  Java copy into the fresh array is a memcpy, and the array is then warm for any direct
  upload that follows. The upload stays direct and critical: its source was just written
  by the CPU -- a lane kernel's result, an Adam-updated weight, an `aset` loop's array --
  and that is warm by the probe's own finding. On the 40-step run this alone took the
  baseline from 7.1 s to 5.5-5.7 s, with residency switched off.
- **The cap.** With the budget left at a quarter of this machine's free memory (~30 GB)
  nothing was ever evicted before the collector got to it, every allocation grew the pool
  (5 us a call instead of 1), and the 200-step run was SLOWER with half the uploads than
  with none (17-19 s against 16-16.5). Capped at 64 MB, 256 MB or 1 GB the same run was
  5-10% faster than with no residency and the three were within noise of each other, so
  the budget is `min(free / 4, 1 GB)`, re-derived at every pre-flight refresh
  (`CudaGemm.RESIDENT_CAP`). The cap is what keeps the driver's pool recycling its warm
  blocks; it is not a safety margin. The pre-flight still evicts everything a call is not
  holding, trims the pool and asks again before it would refuse a call, so residency can
  slow a call by one upload but never turn it into a decline.

**The invalidation, as built.** Every in-place write to a packed float array's storage on
each backend reaches `Gpu.written`:

- the interpreter: `LispDoubleFloatArray.setElement` / `LispSingleFloatArray.setElement`
  call `am.ik.rontolisp.FloatArrayAccessHook.written(data)` (named `FloatArrayWriteHook`
  until todo-491 gave it its read half), a static hook in the root
  package (it must not name any accelerator: `-Pweb` cuts `am.ik.gpu` out by
  substituting `eval/LinalgGpu`, whose `install` is what points the hook at
  `LinalgGpuKernels::written`). That covers `aset`, `row-major-aset`, `fill`, `replace`
  and every Lisp-level loop, which is every writer there is except the `--simd` kernels
  that bypass the setter: `LinalgSimd`'s `%la-adam-step` (the parameter and both
  moments), `%la-scatter-rows`, `%la-scale`, `%la-rng-fill`, and `VecSimd`'s whole
  `-into` family (`clip-into`, `scale-into`, `matvec-into`, the unary and binary
  `-into`s) call the hook themselves -- and so, since todo-475, does the bulk
  `%read-sequence-packed` primitive behind `read-sequence` over a packed float array
  (`Environment.PackedBuffer.load`), which the first enumeration MISSED: it fills the
  storage through a `FloatBuffer` / `DoubleBuffer` view (`bytes.asFloatBuffer().get(f.data(),
  ...)`), the grep for writes through `.data()` that was meant to catch it saw a read,
  and it is how every model weight arrives (`examples/llama2`). Every other producer
  allocates. With no listener installed the hook is a
  volatile read, and a write to an array that is not resident is the volatile read plus
  one identity compare (`CudaResidency.lastDropped`), so an `aset` loop pays the monitor
  once per array, not once per element.
- the JVM class output: under `--gpu` the class carries `_gpuWritten(Object)`,
  `if (_gpuInited != 0) RontoLispGpuBridge.gpuWritten(array)` -- the guard is what lets
  `_fvAset1` be emitted before the bridge class is defined, and a write before the first
  device call costs a `getstatic` and a branch. `_fvAset1` / `_fvAset2` / `_fvAsetN` call
  it after every packed store (`JvmFloatArrayRuntimeBuilder`, which takes the method
  reference when the GPU runtime is emitted); `JvmLinalgKernelCompiler` calls it after the
  whole attempt chain for the four members that write a caller's array -- `%la-adam-step`
  (arguments 0, 2, 3), `%la-scatter-rows`, `%la-scale`, `%la-rng-fill` (argument 0) --
  whichever rung ran; `JvmSimdCompiler` calls it on the destination every `vec:`
  `-INTO` kernel returns; and since todo-475 `JvmSequencePackedCompiler` keeps the
  sequence of a `%read-sequence-packed` in a temp and reports it once the
  `_readSeqPacked` helper has returned. The member names are UPPERCASE there; the first
  build compared against `-into`, and the enumeration test below caught it.

**The numbers, final build, same program, same day, same box.** Per training step by
this file's method (`(t40 - t5) / 35`, five interleaved rounds, MEDIAN [range]), plus two
longer runs:

| | before (HEAD of that morning) | with residency + staged downloads |
|---|---|---|
| `--gpu --simd`, per step | 0.133 s [0.102-0.150] | **0.107 s** [0.102-0.113] |
| `--simd`, per step | -- | 0.80 s |
| the 40-step run, whole | 5.7-7.3 s (median 6.7) | 5.6-5.9 s (median 5.75) |
| the 100-step run, whole | 10.8-11.0 s | 9.3-9.7 s |
| the 200-step run, whole | 15.8-16.0 s | 15.4 s |
| steps 100-200, per step (`(t200 - t100) / 100`) | 0.051 s | 0.057-0.061 s |

7.5x against the CPU build at the medians; the step is a fifth shorter on the metric every
earlier round used, and the run-to-run spread that made the device build bimodal (the
cold-page tail, it turns out) is gone from it. **Read the last row before quoting the
first.** A long run warms its own pages: by step 100 the BASELINE is down to 51 ms a step
(JIT warm-up and page warmth together), and there the staged download's extra memcpy
(~20 us/MB) roughly cancels what residency saves on uploads, so the two builds are at
parity or the new one a little behind -- the 200-step runs are within 3% of each other.
What residency buys, on this machine, is the first hundred steps and the predictability;
what it does not yet buy is the steady state, and the items on this seam that would
(a device Adam step over resident parameters; an overlapped or parallel bounce copy; the
downcall cost of `.todo/476`) start from that row, not the first.

**What the profile says is left.** The third profile of the step (T200, `--gpu --simd`,
~1000 samples): `memcpyHtoD` 110, `laEwFS` + `FloatVector.intoArray` 196, `laAdamStep`
67, `Invokers.checkCustomized` 79 -- that last one is `MethodHandle.invokeExact` on the
driver's handles, which are instance fields and therefore not constants to the JIT, so
every downcall goes through the generic invoker (`.todo/476`). The copies are no longer
the first line.

**Tests.** `GpuTest`: `anOperandUploadedOrProducedByARecentCallIsNotUploadedAgain` (the
hit and miss counts, and the chain -- a result as the next operand is a hit),
`aWrittenHostArrayIsUploadedAgainAndTheAnswerFollowsTheWrite`,
`theResidentSetIsBoundedByItsBudgetAndAReleaseGivesTheMemoryBack` (a budget of 8 MB
over 32 calls of 4 MB, then the two-sided free-memory bound after `releaseResident`),
`aCollectedHostArrayTakesItsResidentCopyWithIt`. On each interceptor,
`everyEnumeratedWriterInvalidatesTheResidentCopy`: one program that makes two operands
resident with a bit-identical device member (a broadcast `add`), then writes through
EVERY enumerated setter -- `aset`, `row-major-aset`, the three `%la-` in-place members,
`%la-rng-fill`, five `vec:` `-into` forms -- re-running the member after each and
printing a sum the oracle must print too; run under `--gpu` (the defuns write through
the setter) and `--gpu --simd` (the kernels report themselves, compared against a
`--simd` oracle so the lane sum's fold order is on both sides). It is the test that
found the `-into` case -- and, extended with a `read-sequence` from a binary file in
todo-475, the one that pins the bulk-read writer on both backends.

#### The GEMV, and the matrix that stays (2026-08-22, todo-475)

The first member outside `linalg:`: `vec:matvec`, the matrix-by-vector product, which is
what `examples/llama2`'s decode loop is made of (79 GEMVs per token for `stories15M`,
13 per layer and the classifier head) and which this file had declined twice -- once as
a `linalg:` shape ("a matrix-by-vector product is memory-bound, so its whole cost is one
pass over an operand the device would have to be handed anyway") and once as the
`.todo/475` bullet. Both declines were right per CALL and wrong per TOKEN once the
matrix does not move, which residency (above) had just made possible. The probes are
`.todo/123-gpu-acceleration/MatvecCrossover.java` over `matvec-probe.cu` (the device
columns, and the accumulator question) and `matvec-baseline.lisp` under `--simd` on the
JVM class output (the CPU column); GB10, us per call, best of many:

| rows x cols | elements | `--simd` f32 | device f32 cold | **resident** | kernel only | `--simd` f64 | device f64 cold | **resident** |
|---|---|---|---|---|---|---|---|---|
| 128x128 | 16384 | **2.5** | 16.0 | 8.9 | 5.3 | **4.5** | 17.1 | 8.5 |
| 256x256 | 65536 | **10.0** | 20.6 | 9.7 | 6.1 | 18.5 | 23.6 | **8.8** |
| 288x288 (llama2 q/k/v/o) | 82944 | **12.5** | 22.3 | 10.6 | 7.2 | 24.0 | 25.7 | **9.7** |
| 384x384 | 147456 | 23.0 | 26.5 | **10.7** | 6.9 | 45.5 | 36.9 | **9.9** |
| 512x512 | 262144 | 45.0 | 39.6 | **14.5** | 10.5 | 85.0 | 52.1 | **11.0** |
| 768x288 (llama2 w1/w3) | 221184 | 30.0 | 33.7 | **12.1** | 8.2 | 65.0 | 46.2 | **10.7** |
| 288x768 (llama2 w2) | 221184 | 40.0 | 33.7 | **12.9** | 8.9 | 70.0 | 46.3 | **11.0** |
| 1024x1024 | 1 M | 200 | 101 | **26.3** | 22.4 | 367 | 160 | **15.2** |
| 2048x2048 | 4 M | 900 | 353 | **75.1** | 70.6 | 1500 | 672 | **129** |
| 32000x288 (llama2 head) | 9.2 M | 1467 | 775 | **169** | 163 | 2867 | 1542 | **315** |
| 256x48 / 48x256 (llama2 KV cache) | 12288 | **5.5 / 1.5** | 15.0 / 16.9 | 8.9 / 9.6 | 5.2 / 6.9 | **5.5 / 3.5** | 16.4 / 16.5 | 8.7 / 8.9 |

**Four things to read out of it.** (1) The CPU lane kernel streams a matrix at ~25 GB/s
(the 36.8 MB head in 1.47 ms), so "cold" -- W, x up, launch, y down, the only route the
earlier declines considered -- loses to it until ~2^19 elements and wins by less than 2x
below a million. (2) "Resident" -- W already there, x up, launch, y down -- floors at
~9 us and is ahead from 2^17 (384x384: 2.1x at f32, 4.6x at f64) and by 8-9x at the
classifier head, where the kernel reads 36.8 MB in 163 us, i.e. at ~225 GB/s, the
device's own bandwidth. (3) The 288x288 projections are a TIE (12.5 against 10.6) and the
threshold leaves them on the CPU. (4) The KV-cache GEMVs (written every token) are far
below any threshold.

**The rule that decides the upload is not a size.** The first sight of any matrix
declines and leaves a MARK -- a `CudaResidency` entry with no buffer (pointer 0; it
counts for nothing in the budget, frees nothing when dropped, and `written` clears it
exactly as it clears a copy); the second sight of the same span, unwritten, uploads it
and records the copy; every later one is a hit. So a model's weights are resident from
their second token on, and a matrix the program rewrites between calls (llama2's
KV cache, a Jacobian recomputed per step) is "first sight" every time and never pays the
cold trip it would lose -- measured, 0.87x at 384x384 f32 cold. The alternatives were a
single threshold high enough for the cold trip to win (2^19-2^20: llama2's 768x288
matrices never reach the device and never become resident through this member) or a bet
that the first call's upload will be repaid (it is, for every weight; it is not, for
every rewritten matrix in the 2^17-2^19 band). The mark costs one more `LinkedHashMap`
entry per distinct matrix offered and a synchronized lookup on the call that declines.
The threshold is `Gpu.MATVEC_POOLED_MIN_ELEMENTS = 2^17` over `rows * cols`, `2^20`
unpooled; `Gpu.worthMatvec` is the probe-free size half and `Gpu.matvec` asks the
residency half, which no size can answer. (Metal declined the member at every size until
todo-477; it takes it from `2^21` now, with a compensated accumulator and its matrix the
only resident array -- "Residency and the GEMV on this backend" in the Metal section.)

**The accumulator is a double at both widths, and that was measured too.** A float
accumulator (the `--simd` lane kernel's width) against a double one, resident f32,
us/call: 8.8 against 10.6 at 288x288, 8.9 against 12.1 at 768x288, 23.6 against 75.1 at
2048x2048 (where the matrix is L2-resident and the fp64 units are the limit), 166 against
169 at the head (memory-bound, so equal). And against the scalar defun's rule -- a double
sum narrowed on the store -- over 1024 rows of 768 inexact floats: the double kernel is
bit-identical on **1024 of 1024** rows (worst 0), the float kernel on 268 (worst
2.6e-7), and the `--simd` lane kernel on 144 (worst 5.7e-7). The reason is arithmetic,
not luck: the product of two floats is exact in double, so what separates the device from
the defun is only the ORDER of a double sum, which moves the narrowed float only when the
sum lies within ~1e-16 of a rounding boundary. The 2 us it costs on a small resident call
buys a result that is CLOSER to the cross-backend oracle than the lane kernel it
replaces, which is what lets llama2's story stay byte-identical with the flag on
(below). It is pinned as a relative tolerance plus "more than 99% of rows identical"
(`GpuTest.aSingleFloatMatrixByVectorProductLandsOnTheDoubleAccumulatedOracle`), not as
byte-identity, because it is not one. At f64 it is the product's few-ulp story
(`aDoubleMatrixByVectorProductAgreesWithTheOracleToAFewUlps`). The float4-load variant
of the kernel was measured and is not faster (211 us at the head, against 169).

**The seam is new on both backends, and it is a CHAIN.** Interpreter: `LinalgGpu.installVec`
is called from the VEC library's lazy-load hook in `LispEvaluator`, after `VecSimd.install`,
and `define`s `vec:matvec` over whatever is bound -- the lane native or the defun -- with
the same declined-input protocol as every `linalg:` member (and installs the write hook
itself, since a program may never reach `linalg:`). It is a fourth public entry point
into `LinalgGpuKernels`, so `Target_LinalgGpu` substitutes it too. JVM:
`JvmExprCompiler` routes a `vec:matvec` call site to `JvmSimdCompiler.compileGpuMatvec`
whenever the GPU bridge was emitted -- with `--simd` or without -- which emits the device
attempt over temps and on its `null` the lane kernel (which never declines) or the
spliced defun; `JvmGpuTemplate.gpuMatvec` is the bridge method (`ops` key `matvec`), and
`JvmLinalgGpu.QUALIFIED_VEC_MATVEC` is in the emit gate, so a program whose only device
member is a GEMV embeds the bridge -- and, as with `linalg:`, so does any `vec:` program,
because the spliced `vec.lisp` names the member. Declined: anything that is not a packed
rank-2 matrix and a packed rank-1 vector of the same width and matching extent, a mixed
pair (which the defun COMPUTES and the lane kernel refuses -- both outcomes are the
captured binding's, pinned), and the first sight.

**What it bought, on the program it was measured for.** `examples/llama2`, `stories15M`,
256 greedy tokens, JVM class output, three interleaved runs each on this box:

| | tok/s |
|---|---|
| `--simd` | 220, 224, 226 |
| `--gpu --simd` | 282, 285, 292 |

**1.3x, and the story byte-identical across all six runs.** Not the "several hundred
tok/s against 87" the item projected, and the gap is the item's premise rather than the
device: the README's 87 tok/s was stale -- `--simd` alone decodes at ~220 today (every
row of that README was: JVM scalar 65 not 23, wasm-GC `--simd` 125 not 46, the
interpreter 44 not 15-25; all re-measured and rewritten), so the GEMVs are ~2.4 ms of a
4.5 ms token and not most of it -- and of those, the four 288x288 projections per layer
are a tie and stay on the CPU. What moved is the three feed-forward matrices per layer
and the head (two thirds of the multiply-adds). The account, from nsys over the run
(`nsys profile -t cuda`, `cuda_api_sum`; the story ends at BOS after 222 forward passes,
so 4219 device calls is exactly 19 a token): CPU time removed ~2.1 ms a token (the 24
projections' 0.3 ms stay); CUDA API time added 0.72 ms a token, of which
`cuMemcpyDtoH` is 0.5 (avg 26.5 us, median 18.8, 4199 of them -- the synchronous
download WAITS for the kernel, and a GPU that sits idle for ~100 us of attention between
launches answers each launch from its idle clock, which is the ramp `.kb` warned about
at n=64), `cuMemAllocAsync` 0.1, `cuLaunchKernel` 0.05 and `cuMemcpyHtoD` 0.04 (2892
uploads, not 4199: `w1` and `w3` share their `x`, and the second finds it resident).
The remainder of 4.5 -> 3.4 ms is the Java side of 19 calls. So the probe's best-of
12 us per resident call is ~38 us in situ, and the next 2x on this program is not a
GEMV: it is the attention, RoPE and KV-cache loops, which are boxed Lisp and not on any
seam. On the INTERPRETER the flag buys nothing (44 -> 42 tok/s), for the reason every
round of this file has recorded: the tree walk around the kernels dominates.

**The gap it found.** `read-sequence` over a packed float array writes the storage through
a `FloatBuffer` view and reported nothing, on either backend -- a residency invalidation
hole since todo-474 that nothing had reached, because no program read into an array the
device already held. A decode loop is exactly the program that loads weights by bulk
read, so both paths report now (`Environment.PackedBuffer.load` calls the hook;
`JvmSequencePackedCompiler` reports the sequence after `_readSeqPacked` returns) and
`everyEnumeratedWriterInvalidatesTheResidentCopy` on both backends ends with a
`read-sequence` from a binary file.

**Tests.** `GpuTest`: the two-sight rule with the hit count and the re-upload after
`written`, the two precision pins above, the offsets, the declines with a device present,
and a 1000-call leak run; `GpuDeclineTest`: the threshold, the decline conditions on every
machine, the two PTX entry points. `LinalgGpuTest` / `JvmLinalgGpuAccelCompilerTest`:
the dead-flag guard (`#'vec:matvec` is `#<function VEC:MATVEC>` under the flag),
exact-input identity once resident at both widths, the declines as the captured binding's
outcome (errors included), evaluate-once across the chain, and
`theDeviceIsAskedOnTheSecondSightAndTheLaneKernelOnTheFirst`, which is `.kb/vec.md`'s
f32 reduction probe as a matrix row -- the defun prints 16778240, the lane kernel
16777984, the device the defun's figure -- so `(probe) (probe)` under `--gpu --simd`
prints `(16777984 16778240)` and the chain is legible from Lisp; `LinalgGpuDeclineTest`:
a GEMV below the threshold is untouched everywhere.

#### A result comes home on first host touch (2026-08-23, todo-491)

The round after the GEMV, and the one that replaced the design "Device residency, built"
chose: the host array is no longer the source of truth while a chain runs. `.todo/491`
was filed off the fourth profile of the training step (the one that found the
register-tiled GEMM was not the first line): every device result was downloaded --
220 MB a step at the notebook's shapes, 37534 `cuMemcpyDtoH` over a 200-step run, 44 GB
-- and about 40% of the step was that traffic and the waits around it, because the
download of a `(4 256 1536)` activation costs more than the product that made it. This
section is the record of what was built, in four parts: the LAZY result, the READER
enumeration that makes it sound, the RESIDENT tier of members that it unlocked, and the
numbers.

**Lazy results.** `Gpu.lazyResults(true)` -- which both interceptors switch on at install,
and which is off by default so the library's own contract ("`out` is filled when the call
returns") holds for any other embedder -- makes every member's `finish` skip the
download: the result buffer is recorded in `DeviceResidency` as the array's DIRTY copy
(`Entry.dirty`: the device holds the bytes, the host array holds zeros), and an in-place
member (`rngFill`, the Adam step, the clip scale) marks the buffer it wrote dirty rather
than recording a second one. A dirty copy comes home through exactly one operation,
`Gpu.materialize(host)` -> `CudaGemm.materialize` -> `DeviceResidency.claimDirty` (marks
clean, answers the `Flush` to download) -> the same pinned bounce download the eager path
uses. A clean copy stays resident for the next member, so a chain
`matmul -> div -> where -> softmax -> matmul` moves nothing over the link until something
on the host reads a link of it, and then moves only that link. `materialize` is the ONE
operation of this library that cannot decline: when the host has no other copy of the
bytes, a download the driver refuses is an `IllegalStateException`, not a silent fallback,
because silence there would be a wrong answer. Two rules keep the cache sound:

- **`Gpu.written(host)` now runs BEFORE the write, and materializes first.** Written
  after the store (as it was), a dirty copy's download would have clobbered the store; so
  every setter on both backends reports before it stores (`LispFloatArray.setElement`,
  `_fvAset1/2/N`, the typed loop's `aset`), and every in-place kernel's call site reports
  before the kernel runs. A dirty copy then comes home, the entry is dropped, and the
  write lands on real bytes. The interpreter's in-place `--simd` kernels still report
  after the write and are still correct, because they read the array through `data()`
  first, which materialized it; `read-sequence`'s bulk primitive is the one that reports
  first and reads `storage()`.
- **The device never drops a dirty copy on its own.** Every path in `DeviceResidency`
  that lets an entry go -- the LRU eviction in `put`, `evictAll` from the pre-flight, a
  replacement at a different span, `offeredBefore` -- turns a dirty one into a
  `DeviceResidency.Flush` (the host array held STRONGLY, the pointer, the span), and
  `CudaGemm.settle()` downloads every flush immediately after the call that produced it
  and queues the pointer for the next drain. Immediately, because between the drop and
  the download the array has no entry and a reader would see nothing to materialize; and
  queued rather than freed, because an eviction inside `stage` runs BEFORE the launch that
  reads the buffer. The LRU evicts CLEAN copies first and a dirty one only when no clean
  one is left: evicting a clean copy costs at most one later upload, a dirty one costs a
  download now. A dirty copy whose array the collector reclaimed is unreadable and is
  simply freed. `lazyResults(false)` brings every dirty copy home first (`claimAllDirty`),
  which is what keeps `GpuTest`'s eager assertions honest whatever ran before them in the
  fork.

**The budget, re-derived twice.** Lazily neither the 1 GB `RESIDENT_CAP` nor the quarter
share applies: the budget is everything the device has less an eighth of it (never less
than 512 MB; `LAZY_HEADROOM_SHARE`, refreshed at every pre-flight by `refreshFreeMemory`).
The first lazy build kept the cap and was SLOWER than the eager build it replaced (the
200-step run 17.1 s against 15.4, `cuMemcpyDtoH` 48246 copies / 61 GB against 37534 /
44 GB): the autograd graph keeps a step's activations reachable until its backward, so
with ~400 MB of dirty results live the cap evicted them as fast as they were made, by
downloading, and the step paid for the download AND the re-upload. Measured on the
40-step run under `nsys`: cap 1 GB, 7133 `cuMemcpyDtoH` / 8.0 GB; cap 8 GB or 32 GB
(nothing evicted), 2462 / 0.94 GB. Then the BOOK's shapes did it again one size up: with
the quarter share the 3-step run flushed 45 GB of the graph during backward (the share
converges to a quarter, then a half, of what is left once a 64 GB heap and the pool
itself are taken out of this machine's 123 GB), with a half share still 45 GB, and with
the headroom rule nothing -- 8.1 s a step became 6.3. The cap's reason -- the driver's
pool recycling its warm blocks, 1 us an allocation instead of 5 -- is worth a few
milliseconds a step (the 200-step run's `cuMemAllocAsync` went from 0.20 s to 0.44); one
evicted activation is worth more, and the pre-flight still evicts everything a call is
not holding before it would refuse the call. The eager mode keeps the cap and its
measurement.

**Two fast paths, and the one that had to become a ring.** `claimDirty` and `written`
are called once per element from an `aref` / `aset` loop, so each short-circuits on
"nothing dirty" / "nothing resident" (a volatile read) and on "the array I answered for
last time". The second was a SINGLE remembered array, and the first profile of the lazy
build put `DeviceResidency.claimDirty` at the top with 32% of the samples: a loop that
reads one array and writes another (`linalg:concatenate`'s defun, a typed `dotimes` over
two arrays) alternated between them and took the monitor on every element. Both fast
paths now remember the last four arrays (`RECENT`); a loop over more still pays the
monitor and is still correct.

**The reader enumeration.** The writer enumeration of todo-474 has its mirror: every HOST
READ of packed-array storage on each backend materializes first, and a reader that misses
the seam reads the zeros of an array nobody filled. The interpreter has ONE seam: the
records' `data()` accessor, which `LispSingleFloatArray` / `LispDoubleFloatArray` now
override to call `FloatArrayAccessHook.read` -- every reader there is goes through it (the
`--simd` and `vec:` kernels, `aref` through `elementAt`, the printer through
`elementText`, `toGeneralArray`, `read-sequence`'s mirror, a record pattern, Java interop)
-- and the one reader that must NOT, the device interceptor itself, takes `storage()`.
`FloatArrayWriteHook` became `FloatArrayAccessHook` for its second half; `install` points
both at `LinalgGpuKernels` (`written` / `materialize`). The JVM class output has no such
seam and enumerates instead, through `_gpuMaterialize` (`JvmGpuRuntimeBuilder.MATERIALIZE`,
the guard twin of `_gpuWritten`): `_fvAref1/2/N` and `_fvToGeneral` / `_fvToGeneralPrint`
(which is the printer, `equal`, every coercion) at their top (`JvmFloatArrayRuntimeBuilder`);
every argument of every accelerated `linalg:` call site, right after the device attempt and
before any host rung -- the `--blas` rung, the lane rung, the defun -- which is also where
the in-place members' `_gpuWritten` reports moved to, so a device rung that took the
member and left the array resident is not undone (`JvmLinalgKernelCompiler`); every
argument of every `vec:` call site, the `-into` destination as `_gpuWritten` instead
(`JvmSimdCompiler`), and both operands of the `vec:matvec` chain's host rungs; the typed
loops at `hoistArrays`, once per array, since the arrays are loop-invariant
(`JvmTypedLoopCompiler`); `_writeSeqPacked`'s sequence, with `_readSeqPacked`'s reported as
written before the helper (`JvmSequencePackedCompiler`); and every argument of a Java
interop call, which reads a packed array raw (`JvmJavaInteropCompiler`). `_fvDims`,
`_fvLength` and `_fvElementType` read the header only, which is written at allocation and
never stale, and need no seam. The pin on each backend is
`everyEnumeratedReaderMaterializesTheDeviceResult`: one program that makes device results
(a broadcast add, a transpose -- the bit-identical members, so the oracle is the same
program without the flag) and then reads them through every reader above, and writes into
them through `aset`, `%la-scale` and `scale-into` (a write that must bring the result home
first), printing after each; run under `--gpu` against no flag and `--gpu --simd` against
`--simd`. On the interpreter `aDeviceResultStaysOnTheDeviceUntilTheHostFirstReadsIt` pins
the laziness itself through `GpuThresholds.dirtyCount()` -- a count the JVM class output's
embedded copy cannot expose, which is why "really stayed" is the interpreter's assertion
and "same output" the JVM's.

**The resident tier.** With nothing coming home, the members this file REFUSED because a
round trip cannot beat a lane loop became launches with no copy, and every one is offered
ONLY over an operand that is already resident (`Gpu.resident`, a lookup without a hit
count): declined otherwise at any size, so the refusals' measurements stand. All of them
compute in double and narrow on the store -- the CPU kernels' own rule for exactly these
members -- so all are bit-identical to them, which the tests assert as equality; the one
wrinkle is `sqrt`'s NaN, which the device signs and `Math.sqrt` does not, so the kernel
canonicalizes it.

| member (`am.ik.gpu`) | `linalg:` shape | kernel |
|---|---|---|
| `zip(op, a, b)` | `add` `sub` `mul` `div` `maximum` `minimum` and the five masks `greater` `greater-equal` `less` `less-equal` `equal` at an EQUAL shape | `zip_fXX`, `bin_op` in double; `BIN_GT .. BIN_EQ` = 6..10 |
| `scale(op, a, s, swap)` | the same eleven with a SCALAR on either side (`laEwFS` / `laEwSF`'s shape) | `scal_fXX`, the scalar a double whatever the width |
| `map(MAP_SQRT .. MAP_SIGN)` | `sqrt` `abs` `negative` `sign` | the map kernel's cases 12..15, in double (`MAP_LIBM_OPS` = 12 is where the size threshold stops applying) |
| `where(m, x, y)` | `linalg:where`, hence `torch:masked-fill`; any operand a scalar, the mask either width | `where_fXX` over a 4-stride layout |
| `adamStep(x, g, m, v, rule)` | `%la-adam-step`, IN PLACE: x, m, v stay resident and dirty | `adam_fXX`, every step an `_rn` intrinsic so nothing contracts into an FMA |
| `copy(a, sa, out, so, dims)` | `reshape` (hence `expand-dims`, `squeeze`, `flatten`), the plain rank-2 `transpose`, `%la-gather-strided` (hence `slice`, `broadcast-to`), `concatenate` (`torch:cat`, one copy per input into its slab, the resident input first so the output is resident for the rest), and `%la-scale` through `scale` in place | `copy_fXX`: one source and one destination stride per axis, either sign |

The size-thresholded members (the products, the libm maps, the broadcast, the gather,
the fold) also take a resident operand at ANY size -- `worthOrResident` in `Gpu`, and the
interceptors' pre-checks say `worth || resident` -- because the trip the threshold exists
to amortize is not being paid. The ones that stayed on the CPU, and what they cost the
step at the notebook's shapes, are under "what is left" below. On the JVM the new members
are `JvmLinalgGpu.handles` entries like any other; `concatenate` is the first
EXTENDED-only member with no lane kernel (`LinalgKernelCallLayout` gained its `:axis`
shape, `JvmLinalgKernelCompiler` its arity), and the interpreter defines `transpose` at
arity 1..2 now. `GpuTest.theResidentTierIsOfferedOnlyOverAResidentOperandAndLandsOnTheCpuKernelsBits`
and `theStridedCopyIsTheCopyMembersOverAResidentOperandAndAScaleRunsInPlace` pin the
library; `theResidentTierRunsOverAResidentOperandAndLandsOnTheCpuKernelsBits` on each
interceptor runs every member over a device result against the oracle, and the
interpreter's asserts the hit count moved; `theResidentTierDeclinesWithoutAResidentOperandAndTheCpuRunsUnchanged`
is the other half. Metal declined the whole tier and `lazyResults` in this round (on
unified memory the copy home is a memcpy, and its cost had not been measured there);
`bin_op` in `gemm.metal` gained the five masks so `bcast` answers them. The Apple half was
built and measured as todo-494 -- "Lazy results and the resident tier on Metal" in the
Metal section: the tier lands on the CPU's bits through binary64 in SOFTWARE, the mode
works and is pinned, and the interceptors do not switch it on there, because it measured
a tie at the notebook's shapes and a loss at the book's (`Gpu.lazyResultsIfWorthwhile`).

**What the profile said was left** (the 40-step run, materializations counted by caller):
`torch:clip-grad-norm`'s `%la-sum-squares` reads every gradient -- 7 MB a step, 760
downloads over 40 steps -- and stayed a sequential double fold on the host because its
contract is the defun's order, which a parallel reduction cannot keep; `%la-scale` after
it runs in place on the device, so the Adam step finds the gradients resident. The
embedding's `take-rows` / `gather` / `scatter-rows`, and the few-cell folds of
`%t-unbroadcast` (a fold with fewer than 256 output cells was declined as a
single-threaded device loop, so its big operand came home). All of them moved the same
day, which is "The index tier and the clip norm" below; the loss scalar is what is left.
And the host array of a lazy result is still ALLOCATED -- a zeroed 6 MB Java array per
activation that may never be read -- which is the representation question
`[rank, dim..., data...]` makes hard and `.todo/492` holds.

**Numbers** (GB10, JVM class output, `train-gpt-soseki` at the notebook's shapes, the
method of every table above):

| | before (todo-474's final build, 2026-08-22) | lazy results + the resident tier (2026-08-23) |
|---|---|---|
| `--gpu --simd`, per step, `(t40 - t5) / 35` (median of 3) | 0.107 s | **0.085 s** (4.51 - 1.52) |
| steady state, `(t200 - t40) / 160` | 0.057-0.061 s | **0.038 s** (10.56 - 4.51) |
| the same with `-XX:+UseParallelGC -Xmn4g` | -- | **0.024 s** (7.0 - 3.18) |
| `--simd`, per step / steady state | 0.80 s / 0.85 s | unchanged |
| the 5 / 40 / 200-step runs, whole (medians) | 1.9 / 5.75 / 15.4 s | **1.52 / 4.51 / 10.56 s** (ParallelGC: 3.18 / 7.0) |
| `cuMemcpyDtoH` over the 200-step run, count / bytes / API wall | 37534 / 44 GB / 2.04 s | **6737 / 2.34 GB / 0.12 s** |
| `cuMemcpyHtoD`, count / bytes / API wall | 36723 / 21.7 GB / 0.86 s | **27076 / 3.6 GB / 0.43 s** (the median copy is now a layout buffer of a few bytes) |
| `cuCtxSynchronize` | 0.70 s | 0.35 s |
| kernel time, all | 1.63 s | 1.84 s (`zip` 0.20, `scal` 0.17, `adam` 0.12, `copy` 0.03, `where` 0.01 -- the members the host used to run) |
| `cuMemAllocAsync` | 0.20 s (2.0 us a call) | 0.44 s (4.5 us a call: the pool grows without the cap -- 2 ms a step, the price of not evicting) |

At the BOOK's shapes (the full novel, 6 layers, 6 heads, batch 64; `-Xmx64g
-XX:+UseParallelGC -Xmn8g`, `(t13 - t3) / 10`) the step went 9.9 s -> 8.1 (the half
share, still evicting) -> **6.3 s** (the headroom rule): the arithmetic is 0.2 s of it,
the copies were the second line (a 3-step run plus its 800 sampled tokens now moves
~5 GB down, the sampling's softmax and layer-norm reads most of it, against 88 GB), and
the third -- a zeroed 100 MB host array per result, read or not, and the collector's
share of it -- is now the first. That is `.todo/492`.

Read the three rows of the step together. Against `--simd` the flag is **9.4x** on the
README's own metric (it was 7.5x), **22x** at the steady state and **35x** with the
parallel collector, where a 200-step run is 7 s; the download bytes fell nineteenfold and
the count 5.6-fold (the rest is the host reads listed above, `%la-sum-squares` first). The
first lazy build, which kept the 1 GB cap, measured 17.1 s for the same 200 steps.


#### The index tier and the clip norm (2026-08-23, todo-493)

The round after the lazy results, and the one that finishes them: with results staying on
the device a 40-step `train-gpt-soseki` at the notebook's shapes still downloaded **443 MB
in 1200 copies** -- the "what the profile said was left" list above, counted by the caller
of `_gpuMaterialize`. Five callers, and every one of them a member this file had refused
for a reason that stopped being true once nothing came home:

| caller | copies / 40 steps | MB | the shape, and why it read on the host |
|---|---|---|---|
| `torch:clip-grad-norm` -> `linalg::%la-sum-squares` | 760 | 278.8 | 19 downloads a step -- the model's gradients that were on the device, the largest a `(1536 384)` weight: a sequential double fold whose contract IS the defun's order |
| `torch:index-select`'s backward -> `linalg::%la-scatter-rows` | 80 | 75.0 | the two embedding gradients, `(1024 384)` and `(256 384)`: a scatter-ADD whose repeated indices make its order its value |
| `torch::%t-unbroadcast`'s small axis folds | 240 | 45.0 | 6 a step over a `(256 192)` operand -- `inner` is the head width 192, under `FOLD_MIN_CELLS` |
| `torch:index-select` -> `linalg:take-rows` | 80 | 23.1 | the two embedding TABLES, `(256 384)` and `(138 384)`: an index-driven copy with no device member |
| `torch:cross-entropy-loss` -> `linalg:gather` (through `_fvAref2`) | 40 | 21.6 | the `(1024 138)` log-softmax, read one element per row by the defun's own loop |

All five moved. What is left of that column is **40 copies and 0.16 MB** -- the loss
scalar, which a training loop prints and which therefore has to come home.

**The index tier: three members, all bit-identical, all resident-only.** `take_fXX` (one
kernel, two modes) is `linalg:take-rows` at mode 0 and `linalg:gather` at mode 1, and both
are pure gathers -- `out[i * slab + k] = a[idx[i] * slab + k]` and
`out[i] = a[i * cols + idx[i]]` -- so there is no arithmetic to reorder and the CPU
kernels' bits fall out. `scatter_fXX` is `linalg::%la-scatter-rows`, and it is the one that
needed a design: the CPU adds slab `i` of the gradient into slab `idx[i]` of the table for
`i` ASCENDING, and a token embedding's indices repeat (1024 tokens over a 138-character
vocabulary), so the order is the VALUE and atomics would lose it. The kernel keeps it
without atomics by turning the parallelism inside out -- **one thread per DESTINATION cell,
not per source element**: `Gpu.scatterRows` counting-sorts the indices by destination
first (stably, so each group is ascending) and hands the kernel `start[rows + 1]` followed
by the grouped source slab numbers, and thread `(r, k)` then walks its own group in the
defun's order over a cell no other thread touches. It widens, adds and narrows per step,
which is what the defun's `row-major-aref` store does. All three are offered ONLY over a
resident operand, at any size, for the reason the rest of the resident tier is: a copy
cannot pay for a round trip and does not have to.

`scatter-rows` also inverts the traffic. Its destination is a FRESH zero table
(`%la-like`), so the device pays an upload of 0.2-0.4 MB instead of a download of 1.9 MB --
and the table then stays resident for the clip and the Adam step, which is where the
gradient was going anyway.

**The clip norm, and the one break with the defun's order.** `%la-sum-squares` is
`acc + sum(g*g)` threaded through every gradient of the model -- `torch:clip-grad-norm`
takes the square root of the total -- and it is the largest host read a lazy step makes. It is also the one member of this library that CANNOT be bit-identical, and
that is a property of the shape rather than of the effort. Every other reduction here keeps
its caller's order by giving each output cell one thread and walking it sequentially; a
whole-array sum has ONE cell, so that trick has nothing to divide. Three ways out were
considered:

- a **fixed blocked order both sides use** -- change the defun and every CPU kernel to fold
  in blocks, so the device can match it. Rejected on inspection rather than on effort: no
  single order is good on both machines. A CPU wants few contiguous accumulators (a blocked
  order that is one chain per block is still `n` dependent adds, so it is not even faster
  there); a device wants thousands of grid-strided ones, which on a CPU is either a 1024-way
  strided walk or an 8 KB accumulator array allocated per call -- and the defun is the
  cross-backend oracle, which every other backend and every doc example would have had to
  follow into it.
- **leave it on the host**, which is what todo-491 did, and is the 278 MB above.
- **break the order for this one member**, and say so. That is what was built.

The kernel folds a grid-strided slice per block in a `double` accumulator, adds the block
up in a shared-memory tree, and writes one `double` partial per block; the host adds the
partials in block order and from the caller's seed. Every term is rounded exactly where the
defun rounds it (`__dmul_rn`, `__dadd_rn`) -- only the ASSOCIATION differs, and it is the
better approximation of the two, being a tree. The block count is
`min(1024, ceil(n / 256))`, a pure function of the length, so the answer is REPRODUCIBLE
run to run and the partials that come home are at most 8 KB. The precision contract this
adds is one line: **under `--gpu`, `torch:clip-grad-norm`'s norm is within a few ulps of
the norm every other backend computes, and is not equal to it.** The flag already breaks
bit-identity for products and transcendentals; a clip norm is used as a scale.

**The few-cell fold, and why it is not a member on its own.** `FOLD_MIN_CELLS` is 256
because a fold with fewer output cells is a thin device loop that loses to a CPU that has
the operand. Over a RESIDENT operand the CPU does not have it -- the alternative is a
download -- so the floor there is one WARP (`FOLD_RESIDENT_MIN_CELLS` = 32), the point
below which the launch cannot fill even one. Measured ALONE this was worth nothing: the
240 copies became 240 copies of 768 bytes instead of 192 KB, because the bias gradient it
produced was read by `clip-grad-norm` on the very next line. It is worth its 45 MB only
with the sum on the device too, and the two together are why the column is empty.

**Numbers** (GB10, JVM class output, `train-gpt-soseki` at the notebook's shapes,
`--gpu --simd`; every time is the median of five INTERLEAVED rounds against a jar built
from the previous commit, run 5 / 40 / 200 steps):

| | todo-491 (2026-08-23 morning) | the index tier + the clip norm |
|---|---|---|
| `_gpuMaterialize` downloads over 40 steps, count / bytes | 1200 / 443 MB | **40 / 0.16 MB** |
| `cuMemcpyDtoH` over the same run, count / bytes | 1200 / 465 MB | **1120 / 1.8 MB** (760 of them the 8 KB partial blocks) |
| the 200-step run, whole | 10.05 s | **9.52 s** |
| steady state, `(t200 - t40) / 160` | 0.0366 s | **0.0329 s** |
| the whole slope, `(t200 - t5) / 195` | 0.0442 s | **0.0419 s** |
| per step, `(t40 - t5) / 35` -- the README's metric | 0.0789 s | 0.0831 s |
| new kernel time over the 40-step run | -- | `sumsq` 5.8 ms / 1080, `take` 0.6 / 120, `scatter` 0.44 / 80, `fold` +6.3 / +240 |
| CUDA API wall over the 40-step run | 529 ms | 498 ms |

**Read those rows honestly: 10% at the steady state, and NOTHING over the first forty.**
Both signs reproduced across two independent rounds, so neither is noise on its own. The
traffic removed is ~11 MB a step, and the CUDA API is about 12 ms of the 40-step run's
83 ms step (498 ms over 40) -- everything else is HOST: the zeroed array every result
still allocates and the collector's share of it, which is `.todo/492`. So the ceiling was
never 50%, and the device side did move the right way (529 -> 498 ms). The first forty
steps are mostly JIT, and four more members on the hot path is more for C2 to compile
before the loop settles; that is the best explanation of the row that went the wrong way,
and it is a hypothesis rather than a measurement. The honest summary is that the change
buys the steady state and pays for it in warmup. What the empty column buys is not the
10%: it is that every future kernel, and every host-side saving after it, is now measured
against a step that does not stop.

**The tests.** `GpuTest.theIndexTierIsOfferedOnlyOverAResidentOperandAndCopiesTheCpuKernelsBits`
(the library: every member over a resident table against a Java oracle, with repeating
indices, and every one declining before the table is resident) and
`theSumOfSquaresFoldsInBlocksAndIsReproducibleWithinAFewUlpsOfTheSequentialSum` (closeness,
reproducibility, the seed and a slice); `theIndexTierRunsOverAResidentTableAndLandsOnTheCpuKernelsBits`
on each interceptor, against the same program without the flag, plus
`theIndexTierDeclinesWithoutAResidentTableAndTheCpuRunsUnchanged` on the interpreter and
`theIndexTierAndTheClipNormAreInTheEmitGate` on the JVM; and
`theClipNormFoldsInBlocksOnTheDeviceCloseToTheSequentialSumAndReproducibly` on both, which
asserts CLOSENESS rather than equality because that is the contract. Metal declines all
four members and says why: it does not run lazily, so it has no download to save.

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

**For the ELEMENT-WISE members the chain has no library rung either, and its CPU rung is
bit-identical to the defun**, which is why they have no legible fallback probe of the kind
the product has: `--simd`'s unary ufuncs are byte-for-byte the defun's answers
(`.kb/linalg-simd.md`), so "which CPU rung caught the decline" is unobservable from Lisp.
What the tests pin instead is the composition -- with any combination of the CPU flags an
accepted call answers the DEVICE's bits, and the same ones every time.

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

**And on METAL the wart is not narrow at all: it runs from the threshold to about
n=1500.** Measured 2026-08-22 on the M4 Max native binary, one n x n f32 product, ms per
call (`examples/ml/gpu-matmul.lisp`'s timing loop at each size):

| n | `--simd` | `--gpu --simd` | `--blas --simd` |
|---|---|---|---|
| 256 | 2.49 | 0.24 | **0.06** |
| 512 | 22.8 | 0.43 | **0.20** |
| 1024 | 173 | 1.19 | **0.99** |
| 2048 | 1354 | **4.65** | 9.96 |
| 4096 | 10693 | **20.9** | 64.9 |

Accelerate's f32 gemm holds 2.1 TFLOP/s from n=1024 -- an order of magnitude past what
sixteen cores at the `--simd` column's own per-core rate (13 GFLOP/s) could reach, so it is
the CPU cluster's matrix coprocessor rather than lanes or threads -- and it pays no
per-command-buffer floor. The device's efficiency climbs with n (139 -> 622 -> 1804 -> 3695
-> 6591 GFLOP/s) because the ~77 us floor and the operand copies are fixed while the work is
n^3, so the crossover is where the two curves meet, near n=1500. `worth()` does not move:
it is calibrated against `--simd`, which is what a machine without a tuned library has, and
teaching it about a loaded CBLAS would make a language-independent library depend on one --
the same argument as the n=64-96 band above. The user-facing statement is the Apple Silicon
paragraph in the guide's composition section. Note that the native-binary paragraph above is
a CUDA figure and does not apply here: this table IS a native binary.

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

**The size objection does not survive measurement, and phase 4b is where it came closest
to.** At phase 2 the six class files were 47.4 KB and the PTX 10.4 KB, ~78 KB of constant
pool, against the 62 KB `JvmSimdVectorTemplate` (83 KB base64) that every `linalg` program
under `--simd` already embeds. **Today it is ~222 KB**: 68.7 KB of library classes plus
the 13.3 KB bridge, base64 109 KB, plus the PTX at 113 KB verbatim. Measured end to end on
`train-gpt-soseki.lisp`, a `--simd` class is 417 KB and a `--gpu --simd` class 627 KB.

Two thirds of the growth is the PTX and it is worth knowing WHERE, because it is one
decision away from being halved. Marginal PTX per element-wise member, measured by
compiling each alone:

| member | PTX | member | PTX | member | PTX |
|---|---|---|---|---|---|
| `exp` | 2.9 KB | `tan` | **12.7 KB** | `atan` | 3.3 KB |
| `log` | 4.5 KB | `sin` | **12.5 KB** | `sinh` | 4.5 KB |
| `tanh` | 3.9 KB | `cos` | **12.6 KB** | `cosh` | 3.1 KB |
| `erf` | 4.5 KB | `asin` | 4.8 KB | `acos` | 5.6 KB |

`sin` / `cos` / `tan` are 38 KB of the 66 KB the tier added, because their Payne-Hanek
argument reduction carries a table. They were kept because the RULE is the measurement --
each is 9-22x on the device and the selection rule is "does the CPU pay a libm call" --
and dropping them would be a size decision overriding a speed one. If the blob ever has to
shrink, those three are the place, and it is one line in `gemm.cu` plus one in each
member table.

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
answers for `dot`, `%la-matmul-nd` and the twelve element-wise members;
`JvmLinalgGpu.kernelKey` maps each to its `ops` key (`gpuDot` / `gpuMatmulNd` / `gpuExp`
and its eleven siblings, where the key IS the bridge method name, so no third table sits
between them), and the chain above is emitted at whichever call site the program reaches
-- with the `--blas` rung simply absent everywhere but `dot`, since `JvmLinalgBlas.handles`
still answers for that alone. The element-wise kernels are ARITY 1, which the shared
`emitAttempt` already handles: it loads `arity(member)` temps, and
`JvmLinalgKernelCompiler` already knew these members are unary.

The emit gate is `programUsesSymbol` over EVERY member (`JvmLinalgGpu.qualifiedMembers()`,
fourteen names), so it is no longer `--blas`'s gate: a transformer reaches only the stacked
member and the ufuncs, and a gate on `dot` alone would embed no bridge for exactly the
program this flag is for. Since phase 4b a program whose only `linalg` call is
`(linalg:erf x)` embeds the bridge too, and one that reaches no member still embeds
nothing. Neither flag embeds on a program that never reaches
a product. `--gpu` must NOT drag in the `--simd` bridge: a class that did would need
`java --add-modules jdk.incubator.vector` to run
(`theThreeFlagsAreOrthogonalAndEmbedTheirOwnBridges`).

**The extended (option-form) call sites carry a device rung since phase 3**, which they
did not before: the axis folds and the axes transpose are device members ONLY in that
shape. `JvmLinalgKernelCompiler` claims the option form when EITHER bridge has a kernel
for it, and emits the device attempt with the same `LinalgKernelCallLayout` the lane
attempt uses -- the two kernels take the same parameters in the same order, so no third
table sits between them. `JvmLinalgGpu.kernelKey` answers `null` for a member with no
BASE-shape kernel and `extendedKernelKey` for the option form; a call shape at which no
attempt would be emitted routes to `compileDefault` instead of emitting an empty chain,
which is what a `--gpu`-only build reaching `(linalg:sum a)` does. `--blas` is still
absent there: `dot` has no keyword form.

### The precision contract

`--gpu` **stays out of `ci-spec.yaml`** and the scalar `linalg.lisp` defun remains the
cross-backend oracle, exactly as for `--blas`. What is new is the size of the break and
its cause: the device fuses (above), so at `#d` -- where `--simd` is bit-identical to the
defun -- `--gpu` is not. Over inputs exact at the operand width the results still match
EXACTLY, which is what the exact-input tests assert; over inexact ones they do not, and
the pin is a RELATIVE tolerance.

**Phase 3's strided tier does NOT widen the break, and is the one tier that keeps
byte-identity.** Its kernels read widened to double, compute in double and narrow only on
the store -- `%la-bcast-loop`'s and `%la-fold-axis`'s own rule -- and hold no libm at all,
so a broadcast `sub`, an axis `sum` and an axes `transpose` are bit-identical to the
scalar defun at both widths. That is asserted rather than assumed, on every machine, by
`LinalgGpuDeclineTest.theStridedTierIsByteIdenticalWithTheFlagOnEveryMachine` and its
compiled twin.

**Phase 4b makes that break VISIBLE, and it is a genuinely new exception.** For the
product there exists a class of inputs (exact at the operand width) on which the flag
changes nothing, which is why every example stayed byte-identical. For a transcendental
there is no such class: `(linalg:erf a)` over 16384 inexact elements differs in its last
digits, always. **`--gpu` is therefore the first flag whose results a user should not
expect to match the other backends elementwise**, and it says so in the guide as well as
here. What replaces the byte-identity check is stated below under "the check that
replaced byte-identity".

Measured on the GB10 over the JVM class output, worst PER-ELEMENT relative difference
between the device and the scalar defun over 400 samples of a 16384-element linspace
across each member's own domain (`.todo/123-gpu-acceleration/elementwise-precision.lisp`
run twice, once per flag, and diffed by the script in its header), with the same figure in
ulps of the width and the count of samples that differ at all:

| member | `#d` relative | ulps (differing) | `#f` relative | ulps (differing) |
|---|---|---|---|---|
| `exp` | 2.1e-16 | 0.9 (41/400) | 1.3e-7 | 1.1 (118/400) |
| `log` | 2.1e-16 | 1.0 (27/400) | 1.2e-7 | 1.0 (27/400) |
| `tanh` | 2.2e-16 | 1.0 (50/400) | 1.7e-7 | 1.5 (47/400) |
| `sin` | 2.0e-16 | 0.9 (60/400) | 1.2e-7 | 1.0 (57/400) |
| `cos` | 2.2e-16 | 1.0 (68/400) | 1.2e-7 | 1.0 (83/400) |
| `tan` | 2.2e-16 | 1.0 (68/400) | 1.7e-7 | 1.4 (137/400) |
| `asin` | 3.6e-16 | 1.6 (162/400) | 1.2e-7 | 1.0 (102/400) |
| `acos` | 2.2e-16 | 1.0 (50/400) | 1.2e-7 | 1.0 (30/400) |
| `atan` | 2.2e-16 | 1.0 (108/400) | 1.2e-7 | 1.0 (56/400) |
| `sinh` | 2.2e-16 | 1.0 (28/400) | 1.3e-7 | 1.1 (118/400) |
| `cosh` | 2.2e-16 | 1.0 (36/400) | 1.2e-7 | 1.0 (134/400) |
| `erf` | **1.0e-15** | 4.5 (323/400) | 1.1e-7 | 1.0 (107/400) |

**Read three things out of it.** (1) It is ONE to TWO ulps of the operand width, for
eleven of the twelve -- the two libms simply round the last bit differently, and between
27 and 162 of the 400 samples differ per member, so most of the array agrees exactly and
the disagreement is the last bit of the rest. (2) `erf` is the outlier at ~4.5 ulps at
`#d`, and the reason is on OUR side: the CPU oracle is `%la-erf-1`'s A&S 7.1.6 series
(`.kb/linalg-simd.md`), not a correctly-rounded `erf`, so the device is probably the more
accurate of the two. (3) **todo-123's feared 4.87e-5 on `tanh` does not reproduce here at
either width** -- it is five orders of magnitude away from the 1.7e-7 measured at `#f`,
and that spike figure should not be quoted again.

**One divergence is not a last-ulp one and can be SEEN**: `(linalg:erf #d(-0.0))` over an
array above the threshold prints `-0.0` on the device and `0.0` on the interpreter and the
JVM. It is the same signed-zero wart `.kb/linalg-simd.md` records for wasm's `erf` and it
has the same cause -- `%la-erf-1` multiplies by `(signum x)` and the two spellings of
`abs`/`signum` disagree on a negative zero -- so it is a THIRD implementation of the same
edge rather than a new kind of problem. `sin` and `tanh` answer `-0.0` on both.

The pins are 1e-12 relative at `#d` and 1e-5 at `#f` -- three to four orders above the
measurement, which is the same posture `TorchGradcheck`'s 1e-3 has: loose enough never to
flap, tight enough that a fast-math build, a mis-numbered op code or a lost `-arch` would
fail it instantly.

#### The check that replaced byte-identity

"Byte-identical with the flag and without it" was the acceptance check for phases 1-4a and
it stops being the right one for any program that touches a transcendental over 16384
elements. Three checks replace it, and together they are strictly stronger than the one
they replace:

1. **Byte-identity still holds, and is still asserted, everywhere the device is not
   asked**: below the element threshold (`LinalgGpuDeclineTest.anElementWiseCallBelowThe
   ThresholdIsUntouchedEverywhere`), below the two STRIDED thresholds
   (`aStridedCallBelowTheThresholdIsUntouchedEverywhere`), for the eight REFUSED members
   at any size (`theDeclinedHalfOfTheElementWiseTierIsUntouchedAtAnySize`, over a million
   elements) and for an EQUAL-shaped binary pair at any size
   (`anEqualShapedBinaryOpIsUntouchedAtAnySize`). Those last two are the guard on the
   measurement: they fail the moment someone widens the member set without measuring it.
   **And byte-identity holds where the device IS asked, for the whole strided tier**
   (`theStridedTierIsByteIdenticalWithTheFlagOnEveryMachine`) -- that is a stronger claim
   than the one it replaces, not a weaker one.
2. **Above the threshold the pin is the relative tolerance above**, asserted per element
   rather than per array, and asserted on EVERY machine -- on one without a device the
   difference is exactly zero, so the same test carries both worlds.
3. **`CUDA_VISIBLE_DEVICES=` still makes every flagged run byte-identical to an unflagged
   one**, because the probe then finds no device and every member declines. That is the
   check that the flag is doing nothing behind the scenes, and it survives phase 4b
   unchanged.

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

`LinalgGpu.available` / `description` / `install` / `installVec` are the only entry points into
`LinalgGpuKernels`, which holds the only reference to `am.ik.gpu` from the `eval` half --
so BOTH bindings, the CUDA one and the Metal one, drop out behind the same three
substitutions.
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
retained primary context and a PTX JIT (~26 ms cold), or on a Mac
`MTLCreateSystemDefaultDevice` plus an MSL compile (~15 ms warm, ~45 cold). That is the one way this flag is not
like `--blas`, whose availability check is nearly free.

### Tests: the interceptor

| what | where |
|---|---|
| interpreter, needs a device (`@EnabledIf` on the probe) | `eval/LinalgGpuTest` |
| interpreter, must hold on EVERY machine | `eval/LinalgGpuDeclineTest` |
| JVM: the emit gate, the blob's class list, the declined product -- on EVERY machine | `codegen/jvm/JvmLinalgGpuAccelCompilerTest` |
| JVM: the accepted product, the declines, evaluate-once (base AND option forms), the chain, the order against `--blas` -- needs a device | the same file, `@EnabledIf` |
| the flag is value-less, the REPL pair, the `.wasm` refusal, the `.class` blob | `cli/CliOptionsTest`, `cli/RontoLispCliTest` |

The dead-flag guard is the load-bearing one, as it is for `--blas`: every numeric assertion
in `LinalgGpuTest` would pass just as well on the scalar defun, so `#'linalg:dot` printing
`#<function LINALG:DOT>` under the flag and `#<lambda>` without it is the assertion that
fails when the flag is DEAD. Since phase 4a it is TWO assertions -- `#'linalg::%la-matmul-nd`
has its own, with the double colon its qualified spelling carries -- and since phase 3 it
is TWENTY-FOUR, one per member, plus the complementary list of members that must still be
`#<lambda>` under the flag (`matmul`, `outer`, `sqrt`, `abs`, `negative`, `sign`, `norm`,
`reshape`, `trace`, `argmax`, `argmin`, `softmax`, `mean`, `var`). The compiled half's
gate assertion has cases for the stacked member, for a program whose ONLY linalg call is a
transcendental, and for the three strided call shapes -- the axis folds and the axes
transpose have no base-shape kernel, so the emit gate is the only thing that puts them in.

`LinalgGpuTest` also pins the two order claims above, the fallback target, and the eight
combinations of the three flags over one exact program (which now includes two rank-3
legs). The stacked member adds, in both suites: every batch shape the odometer can hand
the device (plain rank 3, a broadcast right operand, a broadcast left one, rank 4, a
rectangular non-tile-multiple slab, both widths), the three declines that are its own (a
rank-1 operand, a non-affine batch, a stack under the threshold), and the chain pin that a
declined stack lands on the LANE kernel rather than the defun. The strided tier adds, in
both suites: `theStridedTierIsBitIdenticalToTheScalarOracle` over every member at both
widths and ranks 2 and 3 (the claim that separates this tier from the element-wise one),
`everyStridedDeclineRunsTheScalarDefunUnchanged` over the nine shapes it refuses, and on
the compiled side `anOptionFormArgumentIsEvaluatedExactlyOnceEvenWhenTheDeviceDeclines`,
which is the guard the new device rung at the EXTENDED call sites needs. In `am.ik.gpu`
the load-bearing new one is `aBatchIsBitIdenticalToTheSameSlabsRunOneAtATime`: it states
the precision contract as an assertion instead of trusting that the batched kernel calls
the same device function. Phase 3's are `aBroadcastBinaryOpMatchesTheScalarOdometerWalk`,
`anAxisFoldIsTheDefunsOwnSequentialFold` and `aStridedGatherIsThePermutedCopy`, each
against a Java oracle written out longhand -- the op is a kernel PARAMETER there too, so
nothing else catches a swapped constant. `LinalgGpuDeclineTest` is the half
a CI runner runs, and it pins that the flag changes nothing observable -- at a shape above
the threshold as well as below it.

Since 2026-08-22 both suites also carry `everyEnumeratedWriterInvalidatesTheResidentCopy`
-- the pin on residency's invalidation enumeration, described under "Device residency,
built"; it is the test to extend when a new in-place writer of a packed array is added
anywhere.

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
  28 handles collapse to 18 shapes, added to the six `--blas` ones. Without them the
  linker REFUSES the handle (`MissingForeignRegistrationError`) at BINDING time, not at
  call time, and both drivers bind every entry point in their constructor: one missing
  shape fails the whole binding, so the binary reports "`libcuda.so.1` is not present:
  this machine has no NVIDIA driver" ON A MACHINE WITH A WORKING GPU and runs
  unaccelerated. That is how the pinned-download round shipped: it added
  `cuMemcpyDtoH_v2` PLAIN (the same shape as the critical one is a DIFFERENT
  registration), `cuMemHostAlloc` and `cuMemPoolSetAttribute`, none of them in the file,
  and only the native binary noticed. Two things now stand against it: `CudaDriver.open`
  and `MetalDriver.open` answer `null` only when the LIBRARY is absent and let a binding
  failure THROW, so `probe` prints "the CUDA driver could not be bound: ..." with the
  reason instead of blaming the machine; and `NativeImageForeignConfigTest` binds both
  drivers against a lookup that finds everything -- no device needed, so it runs on every
  machine -- and asserts that every shape they actually ask the linker for has an entry
  in the checked-in file. `--blas` is guarded the same way from
  `LinalgBlasDeclineTest` (`.kb/linalg-blas.md`), through the shared
  `NativeImageDowncalls` test helper. Generate them with the tracing agent
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
| needs a DOUBLE-capable GPU on the machine (`@EnabledIf` on the probe) | `am/ik/gpu/GpuTest` |
| needs a METAL device | `am/ik/gpu/MetalGpuTest` |
| must hold on EVERY machine, GPU or not | `am/ik/gpu/GpuDeclineTest` |
| the native-image downcall registration, both drivers, on EVERY machine | `am/ik/gpu/NativeImageForeignConfigTest` |

`GpuTest` pins the checked-in PTX (it is a generated artifact with no other test of its
validity, and a bad regeneration would pass every decline test), the exactness of a
product over exact inputs at both widths, the tolerance over inexact ones, the operand
offsets, a rectangular shape that is not a multiple of the tile, the multi-chunk copy at
n=3072, and the no-leak assertion. Since phase 4b it also pins **every element-wise op
against `java.lang.Math` over a domain the member is defined on** -- the assertion that
catches a mis-numbered op code, which nothing else can: the op is a kernel PARAMETER, so a
swapped constant computes a different member and every value still looks plausible -- plus
the map's own offsets (the destination's header must survive) and its own no-leak run,
since the map path allocates TWO buffers in its own `finally` and the product's leak test
cannot reach it. `GpuDeclineTest` pins that the probe answers without
throwing, that every decline condition declines rather than throws, that the status table
is total, that only the context-destroying statuses are sticky, that the PTX is the
artifact the loader expects with its regeneration command still attached (all FOURTEEN
entry points, and the `erf` and `div` cases in `gemm.cu`, which are the only things anywhere
that check the op-code mirrors), and that `useKernels` is accepted without probing. Since
2026-08-22 `GpuTest` also pins the generator fill bit-for-bit against the sequential walk
at both widths, all three rules and an offset, with its own no-leak run (one buffer, a
path none of the other leak tests reach), and `GpuDeclineTest` pins its threshold, its
decline conditions and -- on every machine, it is pure integer arithmetic --
`rngAdvance` against a 100,000-step sequential walk. Its
element-wise and strided halves pin the thresholds, the bounds -- for the strided tier the
bound is over the whole reachable SPAN of a stride vector, not the element count, which is
what stops a kernel indexing outside the caller's array -- and, the one that matters, that
an op code the library does not name DECLINES rather than quietly computing something. **That last test hands it the REAL checked-in
text and no test anywhere may hand it anything else**: the override is process-wide and
read at probe time, so a placeholder would decide what the whole suite's device compiles,
whichever class happened to run first.

Since 2026-08-22 it also pins residency: the hit on a repeated or chained operand, the
re-upload after `written`, the budget and the release (the sixth free-memory assertion),
and the collected key. Note that the leak tests now run with resident copies in play:
`a`, `b`, `c` reused across 1000 calls are two resident operands and one result replaced
per call, so a run's steady state holds three buffers and the bounds are unchanged.

**The six tests that assert on FREE DEVICE MEMORY hold a `@ResourceLock` and their bound
was widened to 1.5 GB in phase 3.** `cuMemGetInfo` reports the DEVICE, not the thread:
two leak tests running at once each read the other's pool churn as their own drift, and
the JVM backend's fork loads a separate copy of the binding -- its own primary context,
its own PTX module -- for every compiled `--gpu` class it defines. With the strided tier's
tests in the set that drift reached 808 MB against the old 256 MB bound. The lock
serializes them against each other and every leak run is sized so a real leak is 2-8x the
bound (1000 products of three 1.2 MB buffers is 3.5 GB), so the wider bound costs the
assertion nothing. Do not tighten it back without re-measuring the drift.

Everything here is skipped on a machine without a GPU, which is every CI runner this
project has -- so `GpuDeclineTest` is the half that actually runs there, and it is the half
that must never regress.

## What is deliberately NOT here

No residency ON METAL, no axis fold ON METAL, no element-wise member whose scalar cost is one
machine instruction AT AN EQUAL SHAPE, and nothing at all outside `linalg:`. Each is a measured
decline, not an omission, and each needs this file's numbers before it is revisited:

- **The axis fold on METAL** is a refusal with two numbers attached (above), and the
  amax/amin half of it is the one to revisit first if that backend's floor ever drops:
  the sum half cannot come back at all while `%la-fold-axis` accumulates in double.
- **Residency was measured and DECLINED in phase 3, re-derived by the second profile and
  BUILT by todo-474** -- on CUDA. "Device residency, built" is the record, and the
  lesson to carry to Metal (which keeps no copies yet) is that the cache was never the
  hard part: the two things that decided it were the fresh-page cost of a device copy,
  which a different platform will have a different answer to, and a cap small enough
  that the allocator keeps recycling. **Measure a Metal port the same way before
  believing it** -- `FreshPageCost.java`'s question first, then the cap sweep on the
  program. That port, and the `vec:matvec` GEMV that needs it on Metal, is
  `.todo/477`, an Apple-hardware item by design.
- **The optimizer update is on the `--simd` seam (todo-473) and the RNG is on THIS one
  (2026-08-22), and with residency in the copies are no longer the first line of the
  profile.** What is: the f32-array-times-double-scalar loops (scalar by the precision
  contract, `.kb/linalg-simd.md`), the Adam kernel, and the generic `MethodHandle`
  invoker under every downcall (`.todo/476`). A device Adam step -- one kernel over the
  parameter, the two moments and the gradient, all four resident -- is now the shape the
  design said would become cheap; it is not built, and its measurement is the next item
  on this seam if one is filed.
- **A zero-copy route was measured (2026-08-22) and declined; a pinned-staging route was
  measured, declined, and then TAKEN for the download** -- the table in "The second
  profile" is the first record and "Device residency, built" the second: the kernel over
  host memory would be 4x, and it is unreachable from a movable Java heap; the staged
  upload loses to the driver's own pageable copy past 2^18 elements over a WARM array;
  and the staged download wins by a hundred times over a COLD one, which every result
  array is. Measure with fresh arrays before touching either half again.
- **`--parallel` (todo-478, `.kb/simd-parallel.md`) sits strictly BELOW the device decision
  on both backends**: the device attempt runs on the calling thread (so does `CudaResidency`,
  which is not thread-safe), and only what it declines reaches the row-parallel lane kernel
  -- `compileGpuMatvec` / `JvmLinalgKernelCompiler` bind the lane rung to the parallel
  bridge entry, `LinalgGpu.installVec` wraps whatever `VecSimd` bound. Pinned by
  `JvmSimdParallelCompilerTest.underGpuTheParallelLanesSitBelowTheDeviceDecision`.
- **Nothing of `vec:` but `vec:matvec`**, which IS here since 2026-08-22 ("The GEMV, and
  the matrix that stays"), and only over a matrix that is resident or has been offered
  once before unwritten. `vec:matvec-into` is not: it writes a CALLER's array, which the
  device would have to download into and the caller's next write invalidate, and
  `examples/llama2` does not use it; `vec:dot` is one reduction over two vectors the
  device would have to be handed, and loses to the lane kernel at every size. The first
  sight of a big matrix used ONCE is also left on the table by the rule, deliberately
  (16 MB cold would have won 2.7x): a program that runs one GEMV does not care.
- **The per-call cost of an FFM downcall inside a native image is still unexplained**
  (above), and nothing may quote a device figure from that build without measuring it
  first.
- **`sqrt`, `abs`, `negative`, `sign` and the binary `add` / `sub` / `mul` / `div` AT AN
  EQUAL SHAPE** are refusals with numbers attached AS ROUND TRIPS, and the numbers stand:
  over an operand the device does not hold they still decline at every size. Since
  2026-08-23 they are members over a RESIDENT operand ("A result comes home on first host
  touch"), which is not a reversal either: it is the case the refusal's measurement never
  had, a launch with no copy. Re-run `ElementwiseCrossover.java` plus
  `elementwise-baseline.lisp` before offering any of them as a round trip.
- **No lazy HOST allocation**: the host array of a result that never comes home is still a
  zeroed Java array, which at the book's shapes is 100 MB per result. Filed as
  `.todo/492`, with the bytes it costs, under "A result comes home on first host touch".
  Its four neighbours in that list -- `%la-sum-squares`, `take-rows`, `gather`,
  `scatter-rows` -- were built the same day ("The index tier and the clip norm"), and the
  clip norm is the ONE member of this flag whose fold order is not the caller's.
- **No asynchronous command buffers on METAL, and so no lazy results for the
  interceptors there.** Every Metal call commits and waits, so a chain over resident
  operands is the CPU's time plus the device's; lazy results and the resident tier are
  built and pinned on this backend and measured a tie at the notebook's shapes and a loss
  at the book's ("Lazy results and the resident tier on Metal"), so the interceptors ask
  for them only where the backend says they pay (`Gpu.lazyResultsIfWorthwhile`). Encoding
  a chain into fewer command buffers, or waiting only at the first host touch, is the
  lever, and it changes when a slab may be recycled -- the one ordering the residency
  design exists to forbid.
