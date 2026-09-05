# `--gpu`: a matrix product on the GPU, or a decline

Two layers. **`am.ik.gpu`** is a language-independent library that takes a member and either runs it
on a GPU or answers "no" -- CUDA through the driver API, Metal through Objective-C, behind one sealed
`GpuDevice` seam. **The interception layer** is the `--gpu` flag over it, on the interpreter and the
JVM class output; a `.wasm` output refuses the flag outright and always will.

Read `.kb/linalg-simd.md` for the declined-input protocol and `.kb/linalg-blas.md` for the flag whose
posture this copies: **recommended, never required; a machine without the hardware runs the same
programs to the same output.** What is different about a GPU is the fixed cost of a round trip, a
separate machine with its own memory, and -- since residency -- that arrays stop coming back.

User-facing description and end-to-end numbers: `doc/{en,ja}/guides/gpu-acceleration.md` and
`examples/llm-from-scratch/README.md`. **This file is the invariants and the mechanics.** Every number
here is re-derivable from the probes in `.todo/123-gpu-acceleration/` plus the `*-baseline.lisp` CPU
baselines; that directory's README says which probe answers which question. The two calibration
machines: an **NVIDIA GB10** (Grace Blackwell, `sm_121`, 48 SMs, unified addressing, driver 580 /
CUDA 13, aarch64) and an **Apple M4 Max** (40 GPU cores, unified memory, 107 GB working set), both on
Oracle GraalVM 25. A different device changes every number; the SHAPE of each result should survive.

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
download is an `IllegalStateException`.

**Package rule**: language-independent -- no rontolisp package, no external dependency. Direction:
`eval -> am.ik.gpu`, `codegen.jvm -> am.ik.gpu`, `am.ik.gpu -> nothing`.

| class | what it owns |
|---|---|
| `am.ik.gpu.Gpu` | the whole public surface: `available`, `description`, the `worth*` predicates, the members, `written` / `materialize` / `lazyResults` |
| `am.ik.gpu.GpuDevice` | the sealed seam over the two backends: `supportsDouble`, `thresholds`, `lazyResultsPay`, the members |
| `am.ik.gpu.CudaGemm` / `CudaDriver` / `CuResult` | the CUDA half: probe, context and module lifetime, members, pinned bounce buffer; the FFM binding; the status table |
| `am.ik.gpu.MetalGemm` / `MetalDriver` | the Apple half: probe, MSL library, MPS, buffer pool, members; the binding, one handle per selector SHAPE |
| `am.ik.gpu.DeviceResidency` | weakly-keyed identity LRU from host array to device copy, dirty/clean state, flush and free queues, stub backings |
| `am.ik.rontolisp.FloatArrayAccessHook` | the interpreter's two seams: every packed-array store and every read of packed storage reports here first |
| `eval.LinalgGpu` / `LinalgGpuKernels` | the interpreter's interceptor, and the ONE reference to `am.ik.gpu` from `eval` so `-Pweb` can cut it |
| `codegen.jvm.JvmGpuTemplate` / `JvmGpuRuntimeBuilder` / `JvmLinalgGpu` | the compiled call site's glue; the blob; which members the bridge claims |
| `src/main/resources/am/ik/gpu/gemm.cu` / `gemm.ptx` / `gemm.metal` | the kernels: CUDA source and its checked-in artifact, and the MSL, which IS the artifact |

## The API

```java
static boolean available() / String description()
static boolean worth(long n, long m, long p) / worth(batch, n, m, p) / worthMap(n) / worthStrided(n)
static boolean worthFold(long n) / worthMatvec(long rows, long cols)
static void    useKernels(String ptx) / useMetalKernels(String msl)   // embedder with no resources
static double[] multiply(a, oA, b, oB, n, m, p)                       // and a float[] sibling
static boolean  multiply(a, oA, b, oB, out, oOut, n, m, p)            // allocates nothing
static boolean  multiply(a, oA, strideA, b, oB, strideB, out, oOut, batch, n, m, p)
static boolean  map(int op, ...) / bcast(int op, ...) / gather(...) / fold(int op, ...)
static boolean  matvec(w, oW, x, oX, y, oY, rows, cols) / rngFill(...)
static void     written(Object hostArray) / materialize(Object hostArray)
static boolean  resident(Object hostArray)
static void     lazyResults(boolean on) / boolean lazyResultsIfWorthwhile()
// resident-operand only, declined at any size otherwise:
zip / scale / where / adamStep / copy / takeRows / gather / scatterRows / sumSquares
// the FUSED tier: one pass each where torch.lisp composed a chain
gelu / geluGrad / softmax / softmaxGrad / layerNorm / layerNormGrad / dropoutMask
```

Row-major `n x m` by `m x p`. Four load-bearing properties:

- **`worth` and the member re-ask the same question**: `worth` so a caller can refuse before it
  unwraps operands, the member so the check cannot be bypassed. **Every `worth*` is probe-free and
  answers with the POOLED CUDA constant on every machine** -- knowing the threshold in force needs the
  probe. The cost is a band between the constant and a backend's higher threshold in which an
  interceptor derives strides or a permutation and the library declines anyway. A test pins 100k
  `worth` calls under 200 ms with no probe run.
- **The batched pair is one call plus a per-batch ELEMENT STRIDE on each operand** -- one launch for
  the stack. A stride may be 0 (a BROADCAST operand) and then only one slab is copied; the span a
  launch reads is `(batch - 1) * stride + n * m`. That is every `torch:linear` over a `(B T C)`.
- **A member is a PARAMETER, not an entry point**: `map` switches on sixteen `MAP_*` codes,
  `bcast`/`zip`/`scale` on eleven `BIN_*` codes, so one kernel per width however the set grows. An
  unnamed op code is a decline, and each kernel's `default` is the identity rather than a member, so a
  slipped mirror cannot silently answer some other function. `GpuDeclineTest` checks the mirrors
  against both kernel texts.
- **The offsets are mandatory.** The compiled backend keeps a `[rank, dim..., data...]` header inside
  the same array; the interpreter passes 0. The result carries no header, so the caller wraps it.

## The runtime requirement is the driver, and nothing else

`SymbolLookup.libraryLookup("libcuda.so.1", Arena.global())` plus a `downcallHandle` per entry point.
No JNI, no bundled shim, **no CUDA toolkit**. `libcuda.so.1` ships with the driver, which is what
makes this compatible with the no-external-dependencies rule. Run-time NVRTC compilation was tried in
the spike and must not return.

On Apple: `libobjc`, `Metal.framework` and `MetalPerformanceShaders.framework` are the OS, the MSL
compiler is the OS, and there is no Xcode. `MTLCreateSystemDefaultDevice` is the only C entry point;
everything else is `objc_msgSend`, which on arm64 must be CALLED through a prototype matching the
selector rather than as the variadic it is declared as -- **`MetalDriver` holds one handle per
selector SHAPE**. A selector taking an `MTLSize` by value needs the struct layout; sending it through
a `long` shape is an immediate SIGBUS, not a wrong answer.

## The kernels: PTX checked in, MSL compiled at run time

`gemm.cu` is the source, `gemm.ptx` what `nvcc` makes of it; both under
`src/main/resources/am/ik/gpu/`, and `cuModuleLoadData` hands the PTX to the driver, which JIT-compiles
it for the card present. Regenerate the pair together (a DEVELOPER-only toolkit requirement):

```bash
nvcc -arch=compute_75 -fmad=false -ptx src/main/resources/am/ik/gpu/gemm.cu -o /tmp/gemm.ptx
sed -n '1,12p' src/main/resources/am/ik/gpu/gemm.cu > src/main/resources/am/ik/gpu/gemm.ptx
cat /tmp/gemm.ptx >> src/main/resources/am/ik/gpu/gemm.ptx
```

`nvcc` cannot prepend a header, so the first twelve lines of `gemm.cu` (`//` comments, hence valid
PTX) are copied onto the front: that is how the regeneration command travels with the artifact.
`GpuDeclineTest` asserts it is still there.

- **`compute_75` (Turing, 2018) is the floor because CUDA 13 refuses anything older.** A card below
  compute capability 7.5 declines at the probe with that as its reason.
- **The load needs no cache plumbing of ours**: 26 ms the first time a given PTX text is seen, 1.4 ms
  after (the driver's `~/.nv/ComputeCache`), so no `cuModuleLoadDataEx` options are passed. MSL is the
  same (~35 ms cold, 2-3 ms warm).
- **`MTLMathModeSafe` is set explicitly** (falling back to `setFastMathEnabled:NO` on an older
  `MTLCompileOptions`): the relaxed default flushes denormals and reassociates, and the strided tier
  claims BIT-IDENTITY with the scalar defun, which neither survives.
- **`Gpu.useKernels` / `useMetalKernels` supply the text for an embedder that carries the CLASSES but
  not the resources**, read by the probe ahead of the resource. Exactly one caller: the JVM backend. A
  call after the probe has run changes nothing and is not an error.

## The probe, and lifetimes

One probe per process, cached in `Gpu`'s static initializer, answering on every machine without
throwing. CUDA is tried first, Metal second -- no machine has both. CUDA sequence: open the library; `cuInit` /
`cuDeviceGetCount` / `cuDeviceGet`; compute capability `>= 7.5` checked explicitly (so the reason is
legible rather than a `CUDA_ERROR_NO_BINARY_FOR_GPU` from the module load);
`cuDevicePrimaryCtxRetain` + `cuCtxSetCurrent`; the PTX resource, `cuModuleLoadData` and
`cuModuleGetFunction` per kernel; one `cuMemAllocAsync`/`cuMemFreeAsync` pair to learn whether this
driver's pool serves per-call memory.

- **Only the stream-ordered allocator is an OPTIONAL symbol**; everything else has been in the driver
  API since CUDA 4, so the binding declines whole rather than half-binding. `CudaDriver.open` /
  `MetalDriver.open` answer `null` only when the LIBRARY is absent and let a binding failure THROW, so
  the probe prints "the driver could not be bound: ..." rather than blaming the machine -- which
  matters because a missing native-image registration fails at BINDING time.
- **Retained once, for the process** (primary context + module); process exit releases them. Every
  partial failure in the probe unwinds what it acquired (`CudaGemm.unwind`).
- **Per call, device buffers are freed on every path** -- success, decline, failure -- in a `finally`.
- **The five `...FreesEveryBufferItAllocates` leak tests measure the POOL, not the device**
  (`CudaGemm.poolBytesInUse`, `GpuTest.driftSample`): `CU_MEMPOOL_ATTR_USED_MEM_CURRENT` is scoped to
  the pool HANDLE this process created, where `cuMemGetInfo` reports the whole DEVICE and drifts
  1.78-1.85 GB against the old 1.5 GB `GpuTest.DRIFT_BOUND` -- close enough to real-leak sizes that
  widening would hide leaks. `GpuTest.driftBound` falls back to `cuMemGetInfo` only for a driver with
  no pool. On Metal the leak question is "does the pool reach a steady state"
  (`MetalGpuTest.aRunOfCallsSettlesTheBufferPoolRatherThanGrowingIt`).
- **Threads.** The driver API is thread-safe and every call owns its buffers, so concurrent members
  are correct without a lock. `DeviceResidency` is NOT thread-safe and the device attempt runs on the
  calling thread, which is why `--parallel` sits strictly below the device decision. Open caveat: a
  copy issued while another thread's kernel is queued on the null stream waits for it, INSIDE the
  critical window; per-thread streams are the fix.

### A DECLINE MUST COST THE DEVICE NOTHING, and that takes three calls in order

A pooled allocation that FAILS still grows the pool as far as it can on the way to failing, so the
high-water mark survives for the process AND against every other CUDA process on the card (one
declined 80 GB product took a 128 GB device from 69 GB free to 1 GB, permanently, while returning
`null` correctly).

1. **A pre-flight**: the buffers' total against `cuMemGetInfo` less 64 MB of headroom, before anything
   is allocated. It costs 6-13 us, so it is AMORTIZED -- remembered, decremented by what was handed
   out, re-asked every 64 allocations or as soon as a request exceeds a quarter of the remembered
   figure (`CudaGemm.allocate`). An erring estimate errs towards REFUSING.
2. **A trim after a failed allocation -- three calls, IN THIS ORDER, or it silently does nothing**:
   `release()` the buffers that DID allocate (a trim finds them in use otherwise); `cuCtxSynchronize`,
   because `cuMemFreeAsync` is STREAM-ordered and the buffers are only QUEUED; `cuMemPoolTrimTo`.

With all three, twelve consecutive declined 80 GB products move free device memory by 0 MB.
`GpuTest.aDeclinedProductCostsTheDeviceNothing` asserts the MEMORY, not the return value.

## `Linker.Option.critical` takes heap segments here too -- with a different bound

`cuMemcpyHtoD` / `cuMemcpyDtoH` take `MemorySegment.ofArray(a).asSlice(...)` directly under
`critical(true)`, offset included. Staging in a per-call confined arena loses at every size and the
gap WIDENS (1.04x at n=8 to 3.09x at n=1024). **So the library never stages for the UPLOAD.** The
download is staged, for the fresh-page reason under residency.

A critical call does not transition the thread to native, so the VM cannot safepoint while it runs.
Two ways that window gets long, two rules, neither "stage it":

1. **The copy is bandwidth-bound**: a copy over `CRITICAL_CHUNK_BYTES = 1 << 26` (64 MB) is SPLIT into
   chunks. 64 MB is ~1.1 ms of copy here (16.9 us/MB); an extra downcall per chunk is nothing.
2. **A device-to-host copy on the null stream also WAITS for the kernel.** A critical `cuMemcpyDtoH`
   straight after a launch holds the thread off a safepoint for the kernel's whole runtime (36 ms at
   n=2048 f64, 283 ms at n=4096, against 548 us and 2.2 ms after an explicit wait). Chunking cannot
   help -- the wait lands on the first chunk -- so the kernel is awaited by a plain,
   thread-transitioning `cuCtxSynchronize` before the result comes back. **The threshold is
   per-device, because a flop count is not a duration**: `SYNC_FLOPS_PER_MULTIPROCESSOR = 1 << 22`
   times the SM count. On 48 SMs that is 2^28 flops, ~0.6 ms. The comparison is `>=`, not `>`:
   n=m=p=512 at f64 lands exactly on 2^28 and must be on the syncing side.

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
missing one: an unknown code is treated as sticky and retires the feature, so a constant that does not
exist but IS in the table with a guessed `sticky` flag can leave a dead context paying full round trips.

- **Any non-zero status declines**, after freeing every buffer. `CUDA_ERROR_OUT_OF_MEMORY` is an
  ordinary decline: this call was too big, the next may fit.
- **A sticky status retires the feature for the process.** The seventeen marked statuses (launch
  failures, uncorrectable memory errors, a destroyed or deinitialized context, a driver mismatch)
  leave the context unusable; `CudaGemm` sets `usable = false`.
- **An unrecognised code is not an error of its own** -- `CuResult.of` answers `null`, `describe`
  still produces a string, `isSticky` assumes the dangerous kind.
- **Metal has no such state.** A command buffer ending in any status but `Completed` is an ordinary
  per-call decline.

## Every threshold, and what fixed it

A threshold sits where the win is UNAMBIGUOUS, not where it first appears: a "win" that is really a
tie is the one way this flag can do harm. Every one was measured against the fastest CPU path
the machine has (`--simd`, JIT-warm), never a flop count, and re-derived per backend.

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
when it is a machine instruction.** At 1.5 M f64 elements `erf` is 124x, `tanh` 22.6x, `exp` 17x,
`log` 13x, `sin` 9.3x -- and `sqrt` 1.4x, binary `add`/`mul` 1.15x; at f32 the device column is FLAT
and the binary ops LOSE. **A member that wins by less than its own measurement error is not a
member.** Two traps in any such CPU column: a CPU figure depends on which widths the PROCESS has run
(1.3-1.9x), and the FIRST shape measured in a process pays ~500 us a call for its first few thousand
device calls.

**The strided tier exists because the element-wise refusal was a refusal of a different call**: it
refused `add`/`sub`/`mul`/`div` at EQUAL shapes, where `--simd` runs a lane loop, while the same ops
in a real `softmax` or `layer-norm` are BROADCASTS, which `.kb/linalg-simd.md` says are a SCALAR
ODOMETER walk in every `--simd` backend. So the same `linalg:sub` is a device member against a
`(4 256 1)` operand and a decline against a `(4 256 384)` one.

**A DECLINED strided call must allocate nothing, and that is an ORDERING rule inside the
interceptor.** This tier sits on `linalg:add`/`sub`/`mul`/`div` -- call sites a program runs
constantly and which mostly decline -- so the size test comes FIRST over a bound that costs nothing,
ahead of the broadcast-shape derivation and the permutation check, both of which allocate an `int[]`.

## The accept rules against the shapes the programs run

Measured with a counting hook on `LinalgGpu.define` -- the one place every interpreter offer passes
through -- keyed by member NAME and by ARGUMENT SHAPES as the rule sees them. The hook is a temporary
edit, not in the tree. **The interpreter answers for both offer layers**: `eval/LinalgGpu` and
`codegen/jvm/JvmGpuTemplate` are two copies of one decision that `GpuOfferDifferentialTest` pins to
agree.

The census: the chapter-2 Transformer makes ~4,100 offers with 212 declines, 191 of them
`%la-scaled-masked-softmax` and its grad refusing an `f(64 1 19)` padding mask against an
`f(64 19 19)` score on `suffixLength`; the chapter-3 GPT makes 12, its `(1 256 256)` mask being a
suffix once the leading extent-1 axis is dropped, and its 634 `torch:clip-grad-norm` offers all
accepted over resident operands; llm offers only `vec:matvec`, **1440 a run declining on SIZE** at
`f(288 288)` = 82944 against `MATVEC_POOLED_MIN_ELEMENTS` 2^17. The GPT's mask is a `double[]` over a
`float` score -- **the only production shape here that exercises the either-width mask clause.**

### The device contract, read against the implementations

Every clause of `GpuDevice`'s javadoc an implementation could silently narrow was read against
`CudaGemm` and `MetalGemm`; **neither narrows anything**. The clauses: `where`/`whereF` mask is
`double[]` or `float[]` of either width or `null`, and any of `m`/`x`/`y` may be a scalar;
`softmax`/`softmaxF` and grads take a mask of either width or `null`, a non-array mask being an
explicit decline; `gemmT`/`gemmFT` may refuse a non-plain orientation (both take all four); `take`
mode 0 is take-rows and 1 is gather; `gemv` is offered only once the matrix has been seen twice
unwritten (`offeredBefore`; `Gpu.matvec`'s `offeredMatvec` runs its bounds BEFORE the size and
residency gate); `sumSquares` answers `null` on a decline; every kernel entry is
`try { ... } catch (Throwable) { return false; }`. Every shape-level accept rule lives in `Gpu`, above
the device; `CudaGemm` adds only `usable` and allocation failure.

**No PRODUCTION code does arithmetic on a threshold, and none may.** Metal's fold threshold is
`Long.MAX_VALUE` and `2 * Long.MAX_VALUE` wraps NEGATIVE. Every site that multiplies, adds to or roots
a threshold is in a TEST; `Gpu`'s own use is `>=` and nothing else. **A threshold is safe to compare
against and unsafe to compute from.** One robustness note: `CudaGemm.where` used to map a non-null
mask that is neither `float[]` nor `double[]` to `mkind` 0 (the SCALAR-mask path) and so would have
computed rather than declined, while `softmaxKernel` wrote the same test as an explicit refusal -- two
spellings of one guard, one failing open. Both are now explicit refusals.

## Precision

Three different breaks with the scalar defun, not the same kind.

**The product FUSES.** `gemm.cu` keeps ONE accumulator per output cell and walks `k` ascending across
tiles and within each tile -- the scalar defun's order, NOT a reordering. What differs is that
`acc += As[ty][k] * Bs[k][tx]` compiles to `fma.rn.f64` / `fma.rn.f32`, rounding once where the defun
rounds twice: a few ulps, not `sqrt(n)`-ish growth. Measured as a fraction of the largest cell of the
f64 oracle: **3.4-5.6e-16 at f64 and 2.1-9.0e-7 at f32 from n=64 to 512** -- and the f32 column is
where a CPU f32 accumulation lands, so **at f32 the divergence is the WIDTH, not the GPU**. Over
inputs exact at the operand width the results match EXACTLY; over inexact ones the pin is a relative
tolerance. A tuned BLAS fuses too and agrees BIT FOR BIT up to n=128, separating from n=192 where
OpenBLAS blocks its `k` loop -- so `theDeviceIsAskedAheadOfATunedBlas` uses n=192 and says why.

The pins are **1e-12 relative at `#d` and 1e-5 at `#f`** -- three to four orders above the
measurement: loose enough never to flap, tight enough that a fast-math build, a mis-numbered op code
or a lost `-arch` fails instantly.

**The strided, resident, index and copy tiers are BIT-IDENTICAL to the scalar defun.** Their kernels
read widened to double, compute in double and narrow only on the store (`%la-bcast-loop`'s and
`%la-fold-axis`'s rule) and hold no libm; a gather and a copy move values. On Metal the same claim is
EARNED by the software binary64 route. `sqrt`'s NaN is the one wrinkle: the device signs it and
`Math.sqrt` does not, so the kernel canonicalizes.

**The one member that CANNOT be bit-identical is the clip norm, and it says so.** `%la-sum-squares` is
a whole-array reduction: every other reduction keeps its caller's order by giving each output cell one
thread, and a whole-array sum has ONE cell. (Rejected: a fixed blocked order both sides use -- no
single order is good on both machines; leaving it on the host -- 278 MB a run of downloads.) The
kernel folds a grid-strided slice per block in a `double` accumulator, tree-adds within the block, and
the host adds the partials in block order from the caller's seed. Every term is rounded exactly where
the defun rounds it (`__dmul_rn`, `__dadd_rn`); only the ASSOCIATION differs. The block count is
`min(1024, ceil(n / 256))`, a pure function of the length, so the answer is REPRODUCIBLE run to run.
**Contract line: under `--gpu`, `torch:clip-grad-norm`'s norm is within a few ulps of the norm every
other backend computes, and is not equal to it.**

### The check that replaced byte-identity

**`--gpu` is the first flag whose results a user should not expect to match the other backends
elementwise**, and the guide says so. Three checks replace byte-identity and are strictly stronger:

1. Byte-identity still holds and is asserted everywhere the device is not asked -- below each
   threshold, for the refused members at any size, and for an equal-shaped binary pair at any size
   (the last two fail the moment someone widens the member set without measuring it) -- **and where
   the device IS asked, for the whole strided tier**
   (`theStridedTierIsByteIdenticalWithTheFlagOnEveryMachine`).
2. Above the threshold the pin is the relative tolerance above, per element, asserted on EVERY machine
   -- on one without a device the difference is exactly zero, so one test carries both worlds.
3. **`CUDA_VISIBLE_DEVICES=` still makes every flagged run byte-identical to an unflagged one.**

`--gpu` **stays out of `ci-spec.yaml`** and the scalar `linalg.lisp` defun remains the cross-backend
oracle, exactly as for `--blas`.

## Device residency: the arrays stay on the device

**Do not change any part of this without reading the two enumerations below**: they are what makes a
cache of device copies sound, and each has a pinning test on each backend.

**What it is.** `DeviceResidency` maps a host array -- the primitive `double[]`/`float[]`, by
IDENTITY, the one object both interceptors already unwrap -- to a device buffer holding a copy of its
elements, with the span it mirrors (`offset`, `bytes`; a different span is a miss). The handle CANNOT
live in the array: on the JVM class output the array IS a bare `float[]` with its header inside it.
Identity is sound rather than merely likely because `make-array :displaced-to` rejects a packed array
outright.

**Buffers are freed at the two moments a stream-ordered free is safe to enqueue**: the start of a
call, before any operand is looked up, and the end, after the launch and the download. A free enqueued
BETWEEN an operand's lookup and its launch would be ordered ahead of the kernel that reads it.
`Gpu.written(host)` drops an entry and QUEUES its buffer without a driver call.

**The keys are WEAK.** Held strongly, every activation and gradient a training step allocates stayed
reachable: heap to 14 GB, the step 2.3x slower. So the key is a `WeakReference` with an identity hash,
a collected key turns up on a `ReferenceQueue`, and the next drain frees its buffer. `LinkedHashMap`
in access order over those keys is the LRU; a lookup presents a transient `Lookup` whose `equals`
matches by referent, so a lookup allocates no reference.

**A device copy to or from a host page the GPU has never touched costs ~9 us per 4 KB**
(`FreshPageCost.java`). What warms a page is a CPU copy INTO it. Two consequences:

1. **Every DOWNLOAD is staged** through one pinned 16 MB bounce buffer (`cuMemHostAlloc` at probe
   time, so every leak test's baseline includes it). **The UPLOAD stays direct and critical**: its
   source was just written by the CPU, hence warm.
2. **The eager budget is a CAP on the pool, not a share of the card**, `min(free / 4, 1 GB)`,
   re-derived at every pre-flight refresh. At a quarter of free memory (~30 GB) nothing was evicted
   before the collector got to it and the run was SLOWER with half the uploads than with none; 64 MB,
   256 MB and 1 GB were within noise of each other and 5-10% faster than no residency. **The cap keeps
   the driver's pool recycling its warm blocks; it is not a safety margin.**

**Residency may slow a call by one upload but must never turn it into a decline.** The pre-flight
evicts everything the call is not holding, trims the pool, and asks again before it would refuse.

### The two seams, and what must report through them

`written` and `materialize` are residency's CONTRACT on the caller. **Every in-place write to a packed
array's storage must come through `written` BEFORE it lands**, or the next call answers for bytes the
array no longer holds. **Every host READ of packed storage must come through `materialize` first**, or
it reads the zeros of an array nobody filled. Both are cheap when they do not matter (a volatile read,
then an identity compare) and never run the probe.

**The interpreter has ONE seam of each.** `LispSingleFloatArray`/`LispDoubleFloatArray` call
`FloatArrayAccessHook.written` from `setElement` and `.read` from the records' `data()` accessor -- so
`aset`, `fill`, `replace`, `aref`, the printer, `toGeneralArray`, `read-sequence`, a record pattern
and Java interop all pass through it. The hook is a static in the ROOT package and must not name any
accelerator: `-Pweb` substitutes `eval/LinalgGpu`, whose `install` points the hook at
`LinalgGpuKernels`. The one reader that must NOT go through it is the device interceptor itself, which
takes `storage()`.

**Three kinds of writer bypass the setter and report themselves**, each found the hard way: the
`--simd` in-place kernels (`%la-adam-step`, `%la-scatter-rows`, `%la-scale`, `%la-rng-fill`), `vec:`'s
whole `-into` family, and the bulk `%read-sequence-packed` (it fills storage through a `FloatBuffer`
view, so a grep for writes through `.data()` saw a READ, and it is how every model weight arrives).
One case looks like a writer and is not: `torch:set-data` REBINDS a tensor's data field.

**The JVM class output has no seam and ENUMERATES instead**, through `_gpuWritten`/`_gpuMaterialize`
(guarded by `if (_gpuInited != 0)`, which lets `_fvAset1` be emitted before the bridge class is
defined): `_fvAref1/2/N`, `_fvAset1/2/N`, `_fvToGeneral`/`_fvToGeneralPrint`; every argument of every
accelerated `linalg:` call site, right after the device attempt and before any host rung; every
argument of every `vec:` call site; the typed loops at `hoistArrays`;
`_readSeqPacked`/`_writeSeqPacked`; and every argument of a Java interop call.
`_fvDims`/`_fvLength`/`_fvElementType` read the header only.

**The one reader that cannot be enumerated** is `runtime.RontoFloatArray`, the `rontolisp:jvm-export`
handle: a class OUTSIDE the generated program, so it adopts the generated class and resolves its
`_gpuMaterialize`/`_gpuWritten` reflectively (`.kb/jvm-export.md`). It is also why the boundary
(`floatArrayResult`) does NOT materialize when it hands a result over.

**The pins**: `everyEnumeratedWriterInvalidatesTheResidentCopy` and
`everyEnumeratedReaderMaterializesTheDeviceResult`, on EACH interceptor. **These are the tests to
extend when a new in-place writer or a new raw reader of a packed array is added anywhere in the
tree.**

### Lazy results, and the result that has no host array

`Gpu.lazyResults(true)` makes every member's `finish` skip the download: the result buffer becomes the
array's DIRTY copy, and an in-place member marks the buffer it wrote. A dirty copy comes home through
exactly one operation, `Gpu.materialize`. A clean copy stays resident for the next member, so a chain
`matmul -> div -> where -> softmax -> matmul` moves nothing over the link. Off by default, so the
library's contract ("`out` is filled when the call returns") holds for any other embedder;
`Gpu.lazyResultsIfWorthwhile()` is what the interceptors call and asks `GpuDevice.lazyResultsPay`.

**The device never drops a dirty copy on its own.** Every path that lets an entry go -- LRU eviction,
the pre-flight's `evictAll`, a replacement at a different span -- turns a dirty one into a `Flush`
(host array held STRONGLY, the pointer, the span), and the owner downloads it IMMEDIATELY after the
call that produced it: between the drop and the download the array has no entry and a reader would see
nothing to materialize. The pointer is QUEUED rather than freed, because an eviction inside `stage`
runs BEFORE the launch that reads the buffer. The LRU evicts CLEAN copies first.
`lazyResults(false)` brings every dirty copy home first.

**The lazy budget is not the eager cap**: everything the device has less an eighth
(`LAZY_HEADROOM_SHARE`, never below 512 MB), refreshed at every pre-flight. Keeping the 1 GB cap made
the first lazy build SLOWER than the eager one -- the autograd graph keeps a step's activations
reachable until its backward, so the cap evicted them as fast as they were made.

**A lazy result allocates no host array: it is a STUB.** The value the program holds is still the
array, but SHORT -- the header alone on the JVM, an EMPTY `float[0]`/`double[0]` on the interpreter,
distinct per result. Every header-only reader (`array-dimensions`, `length`, `array-rank`,
`array-element-type`, the type predicates, the printer's prefix) works on it unchanged, which is why
it is a short array of the same type; and the stub is the IDENTITY residency is keyed on. The elements
live on the device while the entry is dirty and -- from the first host touch -- in a BACKING the
library allocates (`DeviceResidency.storageFor`), held in a second weak-keyed map for as long as the
stub is reachable. Four rules keep it sound:

- **A stub is told from a full array STRUCTURALLY**: a result array exactly the prefix ahead of the
  result offset (`Gpu.fitsResult`: `length == offset`) is a stub; one long enough to hold the span is
  a full array; anything between is a caller's mistake and declines.
- **A stub offered as an OPERAND has the extent of the span it stands for** (`GpuDevice.extent`: its
  own length, or the end of its entry's span, or its backing's length, whichever is larger). Asking
  `a.length` made every stub operand and every stub result decline.
- **A stub is in one of three states and never a fourth** -- a dirty device copy and no backing; a
  copy and a backing; a backing alone. A stub with neither is a broken invariant and `source` throws
  rather than uploading zeros.
- **The two seams ANSWER the array to use.** `materialize` and `written` return `Object`: the host
  array itself, or a stub's backing. On the interpreter `data()` answers what the hook answers, except
  that the in-place `--simd` kernels report `storage()`, the stub, not the array `data()` handed them.
  On the JVM every enumerated site REBINDS its local to the answer, and `_fvLength` at rank 1 reads
  `d[1]` rather than `d.length - 2`.

**The unswap rule: a host rung that answers its argument answers the CALLER's object.** Under this
mode the argument a host rung was handed is the BACKING; let that escape and the program holds two
objects for one storage -- a device member offered the backing keys a second entry that a write
through the stub never invalidates: a silent stale read. So every call site that hands a backing to a
host rung maps the answer back through `_gpuUnswap(result, original, handed)`. The interpreter has no
such problem (its value is the RECORD). **The one hole, named rather than closed, is Java interop.**

**The fast paths remember FOUR arrays, not one.** `materialize`/`written` are called once per element
from an `aref`/`aset` loop, so each short-circuits on "nothing dirty"/"nothing resident" and on "the
array I answered for last time". One remembered array was not enough: a loop that reads one array and
writes another alternated and took the monitor on every element. The read ring holds `(host, storage)`
PAIRS as one immutable object per slot, so a reader racing the writer never sees one host's storage
under another's.

### The tiers that exist only over a resident operand

**Every one is offered ONLY over an operand the device already holds** (`Gpu.resident`, a lookup
without a hit count), declined otherwise at any size, so the refusals' measurements stand untouched.
All are bit-identical to the CPU kernels they replace. The size-thresholded members also take a
resident operand at ANY size (`worthOrResident`).

| member | `linalg:` shape | kernel |
|---|---|---|
| `zip(op, a, b)` | the eleven binary ops at an EQUAL shape | `zip_fXX`, `bin_op` in double |
| `scale(op, a, s, swap)` | the same eleven with a SCALAR on either side | `scal_fXX` |
| `map(MAP_SQRT .. MAP_SIGN)` | `sqrt` `abs` `negative` `sign` | map kernel cases 12..15 (`MAP_LIBM_OPS` = 12 is where the size threshold stops applying) |
| `where(m, x, y)` | `linalg:where`, hence `torch:masked-fill` | `where_fXX` over a 4-stride layout |
| `adamStep` | `%la-adam-step`, IN PLACE | `adam_fXX`, every step an `_rn` intrinsic so nothing contracts into an FMA |
| `copy` | `reshape`/`expand-dims`/`squeeze`/`flatten`, rank-2 `transpose`, `%la-gather-strided` (`slice`, `broadcast-to`), `concatenate`, in-place `%la-scale` | `copy_fXX`: one source and one destination stride per axis, either sign |
| `takeRows` / `gather` / `scatterRows` | `take-rows`, `gather`, `%la-scatter-rows` | `take_fXX` (two modes), `scatter_fXX` |
| `sumSquares` | `%la-sum-squares` behind `torch:clip-grad-norm` | `sumsq_fXX`, the ONE member whose fold order is not the caller's |

**`scatter_fXX` needed a design.** The CPU adds slab `i` of the gradient into slab `idx[i]` for `i`
ASCENDING and a token embedding's indices repeat, so the order IS the value and atomics would lose it.
The kernel keeps it without atomics with **one thread per DESTINATION cell, not per source element**:
`Gpu.scatterRows` counting-sorts the indices by destination (stably) and hands the kernel
`start[rows + 1]` followed by the grouped source slab numbers; thread `(r, k)` walks its own group in
the defun's order over a cell no other thread touches. It also inverts the traffic: the destination is
a FRESH zero table, so the device pays an upload of 0.2-0.4 MB instead of a download of 1.9 MB.

### The GEMV, and the matrix that stays

`vec:matvec` is the first member outside `linalg:` (79 GEMVs a token for `stories15M`). It was declined
twice as memory-bound; both declines were right per CALL and wrong per TOKEN once the matrix stops
moving.

**The rule that decides the upload is not a size.** The first sight of any matrix declines and leaves
a MARK -- an entry with no buffer, counting for nothing in the budget, cleared by `written` exactly as
a copy is; the second sight of the same span, unwritten, uploads it; every later one is a hit. So
weights are resident from their second token, and a matrix the program REWRITES between calls
(llm's KV cache) is "first sight" every time and never pays the cold trip it would lose (0.87x at
384x384 f32 cold).

**The accumulator is a double at both widths.** Over 1024 rows of 768 inexact floats the double kernel
is bit-identical on **1024 of 1024** rows, a float kernel on 268, the `--simd` lane kernel on 144 --
the product of two floats is exact in double, so only the ORDER of a double sum separates device from
defun. It is what lets llm's story stay byte-identical with the flag on. Pinned as a relative
tolerance plus ">99% of rows identical".

**The seam is a CHAIN on both backends.** Interpreter: `LinalgGpu.installVec`, called from the VEC
library's lazy-load hook after `VecSimd.install`, and it installs the write hook itself since a
program may never reach `linalg:`. JVM: `JvmExprCompiler` routes a `vec:matvec` call site to
`JvmSimdCompiler.compileGpuMatvec` whenever the GPU bridge was emitted -- with `--simd` or without.
Declined: anything not a packed rank-2 matrix and a packed rank-1 vector of the same width and
matching extent, a mixed pair, and the first sight.

### The collector, and the flags that do and do not help

**On CUDA the library ASKS for a collection**: the LRU evicts CLEAN copies on its own and, when only
DIRTY ones are left, STOPS and sets `collectionWanted`; the owner runs `System.gc()`, drains what the
collector released, and only then evicts what is still over budget, as flushes. At most once per
eighth of the budget PRODUCED since the last (`COLLECTION_SHARE`, floor 64 MB). The control:
`-XX:+DisableExplicitGC` makes the book's-shape run 4.5x slower, and what a collection COSTS is the
pages (`-XX:+ExplicitGCInvokesConcurrent`, which never compacts, recovers all of it).

**The rule: the heap's pages have to be ones the program recycles.** **Hand-size a young generation
only where the program FILLS it** (`-XX:+UseParallelGC -Xmn8g` where a step allocates gigabytes);
**otherwise leave the collector alone and add `-XX:+ExplicitGCInvokesConcurrent` to a long run**. Two
traps: `-Xmn` sized for the wrong shape is worse than none (57% slower), and **`-XX:+AlwaysPreTouch`
under G1 is a disaster** (4x) because G1 pretouches every heap expansion INSIDE the pause.

**On Apple silicon this does not transfer**: eagerly the request is never made; under the
asynchronous lazy build it IS made and the default collector answers it best or tied
(`-XX:+DisableExplicitGC` then costs 4-10%). **On a Mac: set `-Xmx` and stop** -- it decides TWO
things, the heap and the pool the device's results live in, sized off the working set less the heap.

## The CUDA backend

**Fifty-two entry points in `gemm.cu`** (eleven in `gemm.metal`, which has no f64 sibling, no
generator and no fused tier), each taking its member as an op-code PARAMETER: the products
(`gemm_f64/f32`, the batched pair, two register-tiled f32 siblings), `map_f64/f32`, the strided
`bcast_*`/`gather_*`/`fold_*`, `rng_fill_*`, `gemv_*`, the resident tier's
`zip_*`/`scal_*`/`where_*`/`adam_*`/`copy_*`/`take_*`/`scatter_*`/`sumsq_*`, and the fused tier's
`gelu*`/`softmax*`/`log_softmax*`/`layer_norm*`/`dropout_mask_*` families. A `_*` name stands for its
`_f32` and `_f64` instances; Metal has the `_f32` half only, plus `copy_strided`. A batched kernel
offsets the three pointers by `blockIdx.z` times the strides and calls the SAME `gemm<T>` device
function, which is why a batched cell folds `k` bit-identically to an unbatched one. `batch == 1`
still launches the PLAIN kernel.

**One stride per operand, not an offset table -- and the decline that buys.** The CPU kernel walks the
batch axes as a mixed-radix odometer; the device adds `blockIdx.z * stride`. They agree exactly when
every axis's stride is that one stride times the axis's weight in the counter: true for a contiguous
batch of any rank and for a wholly broadcast operand (stride 0), FALSE for a broadcast axis under a
non-broadcast one -- `(2 1 40 40) x (2 3 40 40)`. The interceptors derive the stride in O(rank) and
answer -1 when no single stride reproduces the odometer; -1 is a decline.

**The strided layout rides BY VALUE in the parameter block on both backends.** On CUDA a broadcast
needs `3 * rank` ints; in a small pooled buffer the 192-byte `cuMemcpyHtoD` is synchronous, so it
ordered behind every kernel queued on the null stream and each strided call was a hidden
`cuCtxSynchronize`. It is now a fixed `strided_meta` struct (`4 * Gpu.MAX_STRIDED_RANK` = 64 ints,
unused tail zero, because `cuLaunchKernel` copies the declared parameter size). `take` and `scatter`
keep their index BUFFERS, since an index list has no fixed size.

### The register-tiled f32 GEMM

`gemm_tiled<T, TM, TN>` is a `16*TM x 16*TN` block tile, 16x16 threads, each thread owning a `TM x TN`
patch at rows `ty + i*16` and columns `tx + j*16` -- so a warp's global loads and stores are
contiguous and its shared reads conflict-free -- with operands staged through shared memory 16 deep in
k. Two entry points, `gemm_batched_f32_t4` (64x64) and `_t8` (128x128), both taking the batched
parameter block at every batch size including 1. **f32 ONLY**: at f64 the scarce double units pin
every tile to the same speed and the 8x8 tile spills registers and LOSES.

### The launch pipeline, and what a step is bound by

The step is **device-bound**. Two hidden host-side serializers were found and removed: the
post-launch `cuCtxSynchronize` and the strided layout copy -- 817 syncs and 1056 `cuMemcpyHtoD` a step
down to **53 and 57**, each survivor draining the queue through `awaitQueued`. Launches are ~2.5 us of
API each and overlapped, so **the gap to PyTorch is not launches**: its "eager fp32" GEMMs are TF32
TENSOR CORES (a precision class we deliberately do not use) and the rest is PASS COUNT, which the
fused tier answers. **The zero-copy route was measured and KEPT as it is** (`ZeroCopyRoute.java`
priced four alternatives); any future change re-runs that table first.

## The fused tier

The compositions a transformer step spent a third of its device time on -- the exact GELU, softmax,
log-softmax, layer-norm's normalization (and its affine), the scaled+masked softmax and the dropout
mask, forward and backward -- each as ONE kernel where `torch.lisp` launched a chain of `linalg:`
members. **What a fused kernel buys on CUDA is only the passes it removes**; the launches were already
pipelined.

**The contract is the chain's, rounding for rounding.** Each fused kernel reproduces every member
boundary's rounding (the `(T)` casts in `gemm.cu`), keeps every axis fold ascending and sequential in
a double accumulator, and evaluates `exp`/`erf` at the width as `map_op` does. So a fused member lands
on the bits the chain of DEVICE members would have produced
(`GpuTest.theFusedTierLandsOnTheComposedDeviceChainsBitsAtBothWidths` runs every chain member by
member and fused and asserts equality) and the libm-free three (softmax's adjoint, layer-norm and its
adjoint, the mask) land on the CPU defun's bits
(`theLibmFreeFusedMembersAreTheSequentialReferencesBits`, a sequential Java replay against
`GpuTest.layerNormGradReference`). The GELU adjoint measures 1.8e-12 relative at `#d`, pinned at 1e-9.

**The members are the compositions, on every backend** (`.kb/torch.md` "The fused compositions"): the
seven internal `linalg` defuns (`.kb/linalg.md`) ARE the chains, so nothing moved on any CPU path
(`TorchGradcheck.FUSED_PROGRAM`, ci-spec `torch-fused-compositions`). The two adjoints that fold onto
an accumulated gradient take it as an operand (`OLD`, a null pointer when there is none).

**The offer rule is the chain's.** A fused member with a libm call (GELU, its adjoint, softmax) is
offered from the map threshold or over a resident operand; the libm-free ones from the fused threshold
or over a resident operand, with the fold's cell rule on the row count. The mask is offered exactly as
`rngFill`. An operand the rule is not about (the attention mask, layer-norm's `(len)` weight and bias)
is bounds-checked and staged and does not decide. `linalg:softmax`/`log-softmax` are intercepted in
their `:axis` form over the LAST axis only, and on the JVM that form is an EXTENDED call shape
(`LinalgKernelCallLayout`).

## The tape-side fusions this tier depends on

- **The transposed product.** `gemm<T>` and `gemm_tiled<T, TM, TN>` take two flags, `ta`/`tb`: an
  operand so marked has its `M x K` (or `K x N`) matrix STORED `K x M` (or `N x K`) and the staging
  load indexes it that way. The TILE the fold reads is the same, so **the product is bit-identical to
  the plain product of the transposed copy**
  (`GpuTest.aTransposedOperandIsReadInPlaceAndFoldsOntoTheUntransposedProductAtBothWidths`). Staging
  swaps its thread indices to keep loads coalesced (16x16 loads `As[tx][ty]`, shared tiles padded to
  `TILE + 1`); padding the REGISTER tiles the same way was measured and LOST. **The seam is two
  members, not a flag**: `linalg::%la-matmul-nd-ta` and `-tb`, each arity 2, intercepted where
  `%la-matmul-nd` is -- two members because a member with flag arguments would need a new extended
  call shape on the JVM backend. The portable defuns are the transpose and the product they name, so
  every other backend is untouched. Metal carries them on the same two flags and on MPS's
  `transposeLeft:`/`transposeRight:`.
- **The attention scale and mask are views too**, and `torch:softmax` in its `:axis` form consumes the
  chain as ONE node, `linalg::%la-scaled-masked-softmax (x scale mask fill ax)` with its `-grad`
  adjoint. On CUDA that is the `softmax_*`/`softmax_grad_*` pair with scale and mask folded in: each
  cell read as `(T)(x / s)` then as `fill` under the mask, both roundings reproduced; the adjoint
  applies `where(mask, 0, ·)` then the scale in the store. **The mask must be a TRAILING block of the
  operand** (its dims, leading 1s dropped, a suffix of the operand's) and may be either width; any
  other mask or axis declines to the defun. **"Fusing costs the kernel nothing" is not a premise**:
  reading the mask inside the row kernel cost about what the `where` pass it replaced cost, because
  the row kernels run one thread per row (a tenth of the card) so a load per cell is exposed latency.
  **The mask therefore reaches the row kernel PACKED, one bit a cell**, through a `pack_mask_*` launch
  the same call makes just before (8 us); a lane loads ONE word for its row and the lanes exchange
  bits by shuffle (`simd_shuffle` on Metal). Two kernel-comment facts: a `__shared__` tile per
  template instantiation cost the PLAIN softmax 30% (the tiles are declared once, in the dispatcher),
  and the forward's first pass writes the scaled, masked row into the result as scratch.

**A division by a power of two is launched as the multiply.** A `div.rn.f64` is the one arithmetic
operation this card is slow at. Dividing by a power of two is exactly multiplying by its reciprocal --
two correct roundings of the same real number, for every operand including subnormals, infinities and
negative zeros -- so `Gpu.scale` rewrites `op == BIN_DIV` with an exact reciprocal into `BIN_MUL`, in
the one place both backends pass through. **The reciprocal must be normal at BOTH widths**
(`Gpu.exactReciprocal` / `Gpu.normalPowerOfTwo`), because a backend computing in `float` (Metal) would
otherwise multiply by one that underflowed to zero there. Pins:
`aDivisionByAPowerOfTwoIsTheExactReciprocalsMultiplyAtBothWidths`, `GpuDeclineTest`.

### The decline that was only a materialize

`JvmLinalgKernelCompiler` emits the `linalg:` call site as a chain -- device attempt, `--blas` rung,
`--simd` lane rung, scalar defun -- and used to emit `_gpuMaterialize` over EVERY argument between the
device attempt and the host rungs. That guard exists for the LANE and LIBRARY kernels, which read raw
storage past every access hook; **the defun does not need it**, being compiled Lisp that reports its
own reads and writes. A device-only member has no lane kernel at any flag, so for
`%la-scaled-masked-softmax` the materialize dragged home a score whose FIRST reader is `linalg:div` on
the device: 288 round trips a step, 0.491 -> 0.416 s wall once fixed. **The guard is now emitted only
where a host KERNEL rung follows it** (`simd != null || (blas != null && !extendedCall)`); the write
report for an in-place member is emitted whatever follows.

## The Metal backend

The same feature with a different member set. The flag, the CLI, the interception layer, the decline
protocol and the tests are shared; what is NOT shared is the width, every threshold, and two tiers.

| | CUDA | Metal |
|---|---|---|
| widths | `#d` and `#f` | **`#f` only** -- MSL rejects `double` outright |
| rank-2 product | our tiled kernel | **MPS** above `2^27` per matrix, our tiled kernel below |
| stacked product | our batched kernel | our batched kernel |
| transposed stacked product | `ta`/`tb` on the same kernel | the same two flags, and MPS's `transposeLeft:`/`transposeRight:` above the MPS threshold |
| fused tier | nine members | **eight of the nine** -- the dropout mask stays declined |
| element-wise tier | twelve members | the same twelve |
| broadcast + axes transpose | yes | yes |
| axis fold `:axis` | yes | **not as a round trip, measured**; over a resident operand only |
| generator fill | yes | no -- it needs a `double` |
| `vec:matvec` | from `2^17`, double accumulator | from `2^21`, **compensated float** accumulator |
| lazy results + resident tier | on (`lazyResultsPay`) | on since the command buffers went asynchronous |
| resident set | every operand and result | eagerly **the GEMV's matrix only**; lazily every operand and result |
| index tier + clip norm | yes | `takeRows`/`gather`/`scatterRows`/`sumSquares` NOT members (kernels never written) |
| per-call floor | 16-18 us | **77 us** per COMMAND BUFFER eagerly; **15-26 us a member** in a lazy chain |
| per-call memory | the driver's pool | **our own** size-classed pool |
| kernels | PTX generated at build time, checked in | MSL compiled at RUN time, from a string |

**Single float, or nothing.** Every double-taking method answers `false` without touching the device.
The rule is about an operand that ENTERS ARITHMETIC; the one that does not is `where`'s mask, taken at
both widths. Two consequences: **the decline protocol is load-bearing in a way it is not on CUDA** --
`linalg`'s default width is double, so the flag is inert until a program reaches `#f` data -- and
**`GpuTest` no longer describes both backends**: it is gated on a double-capable device and
`MetalGpuTest` answers the same claims at `#f`.

**The rank-2 product goes through MPS and the STACK does not.** The two routes agree BIT FOR BIT, so
which runs is a pure size decision (`n*m*p >= 2^27` for ONE matrix). The stack stays on our kernel
whatever its size because of the ZERO STRIDE: a broadcast operand passes a per-batch stride of 0,
which a batched `MPSMatrixDescriptor` cannot be handed. Bit-identity also means `rowBytes` may be
`columns * 4` rather than `rowBytesFromColumns:dataType:`, which PADS.

**THE AXIS FOLD IS NOT A ROUND-TRIP MEMBER HERE, and either half of the reason would be enough.**
`%la-fold-axis` accumulates in `double` at BOTH widths, so a float accumulator could not be
bit-identical (~1e-5 relative over a 256-long axis). And the amax/amin half, which needs no
accumulator, does not pay: the CPU fold is 85 us over 262144 f32 elements and 410 over 1048576 against
~150 and ~380 here -- a tie at best, and a tie is a decline. So the round-trip fold threshold is
`Long.MAX_VALUE`. Over a RESIDENT operand `fold_f32` exists.

### Precision on this backend

**The strided tier's bit-identity is an ARGUMENT here rather than an inheritance.** MSL has no double,
so `gemm.metal` computes in `float`: `+`, `-` and `*` over two floats are EXACT in binary64, so
rounding the exact result once to float is exactly what compute-in-double-then-narrow produces; `/` is
innocuous double rounding at these widths (53 >= 2*24 + 2); the strict selects and the gather move
values.

**Where that argument does not reach, the shader runs IEEE binary64 in SOFTWARE**: a scalar that is
not a float, every step of the Adam update and the sum fold. A value is its bit pattern in a `ulong`,
every operation unpacks to sign / exponent / 53-bit significand, works in a 128-bit integer so every
intermediate is exact or carries a sticky bit, and packs through ONE rounding step (`f64_pack`) shared
by add / sub / mul / div / sqrt and the exact widening and narrowing of a float. Division is restoring
(55 quotient bits, remainder sticky), the square root digit-by-digit over a 128-bit radicand (56 root
bits), the product four 32-bit partial products. `GpuDeclineTest` asserts no code line of the file says
`double`; the emulation is spelled `f64`.

**This GPU flushes subnormal floats to zero in every float operation, `MTLMathModeSafe` or not.** The
CPU does not, so every float kernel guards it (`bin_op_exact`): an operand that is subnormal, or a
result below `FLT_MIN`, is recomputed on the binary64 route; `abs`/`negative`/`sign` are bit
operations, amax/amin compare through an order key the flush cannot touch, and `where`'s mask test is
a bit test. **And `sqrt` needs `precise::sqrt`**: plain `sqrt` under the safe math mode is 1 ulp off
in ~10% of operands.

**Two transcendentals were FIXED rather than tolerated.** `tanh` and `sinh` measured 1.8e-4 and
3.1e-4 -- MSL's own carry an absolute error floor of ~3.4e-8 near zero, so the relative error grows
without bound as x -> 0. Both are odd with an `x + O(x^3)` expansion, so `gemm.metal` takes the
Maclaurin series to `x^9` below |x| = 1/4 and the builtin above. **`erf` has no builtin at all**, so
the shader runs `%la-erf-1`'s OWN series at float width, which makes Metal's `erf` closer to the
oracle than CUDA's.

Pins: `theStridedTierIsBitIdenticalToTheScalarOracle`, and
`theSoftwareBinary64RouteLandsOnJavasDoubleArithmeticBitForBit` (the scalar forms over 2^18 bit
patterns, the Adam update over three steps, the equal-shape ops).

### The fused tier on Metal

Eight of the nine, in MSL. The row kernels are `gemm.cu`'s -- ONE THREAD per row, thirty-two rows
streamed through a transposed threadgroup tile, two SIMD groups to a threadgroup -- and what makes
them possible is the software binary64 the resident tier already needed.

**Every member boundary goes one of two ways, and which one is not a choice.** With `T = float` and no
`double`, a boundary whose operands are both floats IS that rounding taken once (`bin_op_exact`, flush
guard included), and a boundary against a constant the float grid does not hold (`1/sqrt 2`,
`2/sqrt pi`, layer-norm's `eps`) takes the software route -- exactly what the chain's `scal_f32`
takes. `MetalGpuTest.theFusedTierLandsOnTheComposedDeviceChainsBits` runs each chain member by member
over RESIDENT operands and asserts EQUALITY. Per call 1.4-6.0x, and **a THIRD off the step** (CUDA's
took a quarter) because it removes four command buffers out of five as well as memory passes.

**The offer rule needed a threshold of its own**: `Gpu.offeredRows` took the FOLD threshold for the
libm-free members and this backend's fold threshold is `Long.MAX_VALUE`, so `GpuDevice.Thresholds`
gained a `fused` field -- CUDA passes its own fold threshold, Metal passes `MIN_MAP_ELEMENTS`. These
kernels run one thread per row and fold SEQUENTIALLY with `f64_add` in index order, so
`MetalGpuTest.theLibmFreeFusedMembersAreTheSequentialReferencesBits` holds here too; `row_tile` is a
TRANSPOSED LOAD for coalescing, not a reduction. **One thread per row is the same fact that appears
as a COST in the mask fusion and as a GUARANTEE here.**

**The dropout mask is the ninth and stays declined, on the ARITHMETIC**: Wichmann-Hill's uniform is
three binary64 divisions an element, and a binary64 division here is the software restoring divide.
That is also why `rngFillF` is not a member here. `MetalGpuTest.theDropoutMaskStaysDeclinedHere`.

### The map threshold at the straddling shape: the clock ramp

Measured (`MtlPerRowMap.java`, `log` over a freshly written f32 operand): at 16384 elements the CPU
is 62-66 us, the device 98-137 us back to back, and **419-510 us behind the chain's own gap** --
**the third column is the one the chain gets**, because **this GPU lowers its clocks after ~1 ms idle
and the first command buffer after such a gap costs ~0.5 ms more**. So the crossover is near 2^15 back
to back and at 2^17..2^18 behind the gap, which is where the threshold already is. **The straddle
stays.** The generalization: a size threshold measured back to back is measured in the wrong context
for any member whose operand a REFUSED member produced -- and this is a Metal finding, not a device
finding (the same sweep on CUDA is flat to within 1%).

### Residency and the GEMV on this backend

**The accumulator is a compensated float pair, on the defun's bits without a `double`.** A plain
float sum lands on 229 of 1024 rows; `gemv_f32` keeps its running sum as a float-float pair (the
product's rounding error recovered exactly with an fma, every addition a TwoSum, the SIMD-group fold
pair-wise), carries ~48 bits and is bit-identical to the double-accumulated oracle on **1024 of
1024** rows. `#pragma METAL fp contract(off)` is kept: it is what makes the error-free transforms
mean what they say.

**The threshold is `2^21` and the COLD trip never pays.** On unified memory an upload is a memcpy of
the very bytes the CPU kernel would have streamed, so the two-sight rule is not a refinement here but
the member. The "kernel only" column IS the ~77 us floor until the matrix is several megabytes:
1024x1024 is a tie, 1448x1448 2.5x, 2048x2048 4.8x, 4096x4096 9.4x.

**Residency: kept for ONE kind of array, eagerly -- the matrix of an accepted GEMV.** The full CUDA
design made the step slower at every cap (1-5%): on unified memory the upload residency removes is a
memcpy, while a slab held out of the pool costs the pool a FRESH slab whose first-touch page faults it
pays. `x` and `y` are scratch slabs; a release gives the slabs back to the POOL, not to the device.
**`MIN_RESIDENT_ELEMENTS` = 2^14**, LOWER than the crossover table says, because a declined member
over a resident operand costs a materialize, the CPU loop and the re-upload.

### Asynchronous command buffers

**The interceptors switch lazy results on here** (`MetalGemm.lazyResultsPay()` is `true`) because
under the mode a call no longer waits: the step at the book's shapes goes **4.80 -> 1.81 s**, the
notebook's width 0.083 -> 0.041, and the loss series prints the same four decimals at every step.

**The mechanism.** `MetalGemm.commit` ends every member's encoding: eagerly `commitAndWait`, lazily
commit + RETAIN the command buffer past the call's autorelease pool + a sequence number. Every slab
the call held carries that number as its `fence`; buffers in flight sit in one deque, oldest first.
One queue executes in order, so "every buffer at or below `retired` has completed" is a scalar, and
`settle(slab)` is the only wait there is. It is taken at exactly these host touches and nowhere else:
`stage`'s upload into a slab from the free list (a slab a dropped operand left, which a launch in
flight may still read), and every download. A slab taken as a RESULT needs no wait, since the device
orders its own reuse.

**Failure surfaces at the first host read, never as zeros.** A buffer ending in any status but
`Completed` is learned of after its call answered `true`; `retire` marks the slabs it WROTE lost, and
the results of every later buffer in flight that READ one of them, while a slab the failed buffer only
read is intact. A lost result throws the `IllegalStateException` at `materialize`; a flush of one
records its storage and throws at the read instead, so switching the mode off never throws. Metal
gives a kernel no way to fail on purpose, so
`aFailedCommandBufferSurfacesAtTheFirstHostReadOfWhatItWrote` injects the STATUS through a
package-private seam.

**The budget rules the first asynchronous build got wrong**, each measured:

1. **The pool must be sized WITHOUT the heap** -- on unified memory slabs and heap are one physical
   memory. The lazy pool budget is the working set less `Runtime.maxMemory()` less an eighth.
2. **The resident budget must be counted in the pool's units.** The cache counts the SPANS it mirrors,
   the pool the power-of-two CAPACITY of its slabs; at seven eighths of the pool the LRU never fired
   before the pool filled, and the pool's own pressure path evicted EVERYTHING as flushes (1200-1500
   downloads and 10-12 GB of fresh backings in one call). It is now HALF the pool's
   (`LAZY_RESIDENT_DIVISOR`).
3. **The pool's pressure path evicts a slab's worth at a time** (`DeviceResidency.evictSome`: least
   recently used first, clean before dirty), not the whole cache. And `drop` allocates a dirty copy's
   backing BEFORE the entry is let go of, putting the entry back if the heap runs out.

### Three further Metal findings

- **The strided layout rides by `setBytes:length:atIndex:` here**, not in a pooled slab (which cost a
  4 KB slab acquire/release per strided call, 724 of 4444 pool acquisitions a step, and left a pooled
  buffer a committed command buffer reads). `Gpu.MAX_STRIDED_RANK` keeps the length under `setBytes`'s
  4 KB limit; the packer guards it and throws into the member's own `catch`.
- **The attention scale and mask fold is worth 15% of the step here**, six times CUDA's, because
  `torch:subsequent-mask` is `(linalg:triu (linalg:ones ...))` and `linalg:ones` builds DOUBLE, so the
  causal mask was a `double[]` that `whereF` refused and the chain's fill ran on the CPU over a
  MATERIALIZED score (7.9 ms a call against a 1.6 ms fused forward). The scaled/masked pair is TWO NEW
  ENTRY POINTS (`softmax_sm_f32`, `softmax_grad_sm_f32`) rather than CUDA's
  one-kernel-with-a-dispatcher, because a second MSL entry point has its own threadgroup allocation
  and cannot cost the PLAIN kernels occupancy; `pack_mask` and the row kernel ride ONE command buffer
  as two dispatches of one encoder. Pin:
  `MetalGpuTest.theScaledAndMaskedSoftmaxLandsOnTheComposedDeviceChainsBits`.
- **A `where` mask is a PREDICATE, not a number**, so the width rule does not apply to it:
  `linalg:where`'s test is `(/= m 0)`, an integer test on the raw word. `where_f32` binds its own mask
  as `device const uint*` and tests inline, one word at f32 and two at f64, and the host stages and
  looks the mask up at ITS width (`Call.lookupBytes` / `stageMask`; CUDA's `mkind`/`mwidth` pair
  already did this). It matters because every attention mask in the library arrives as a `double[]`. A
  CAUSAL mask is a trailing block and is folded; a PADDING mask (`(batch 1 length)` over
  `(batch query key)`) is not, so every masked attention that is not causal falls back to
  `%la-scaled-masked-softmax`'s three members, of which this `where` is one. **Nothing else this
  backend refuses on width alone is reached by the reasoning.**
- **Layer-norm's affine was built, measured and NOT kept here** -- worth a quarter of the adjoint per
  call, 45-50 ms of member time a step, and **the step does not see it** (1.680 s declined against
  1.702 fused) because under asynchronous command buffers the removed work overlaps host work that
  remains. **Reopen it when the step is DEVICE-bound at these shapes**; re-take the step, not the
  members. **Re-take a step number after every merge that touches the path**: the first measurement
  said 9.2% because it predated the "stop materializing an argument no host kernel will read" fix.

## The interception layer

The flag over the same `linalg:` seam `--simd` opened and `--blas` widened. Read `.kb/linalg-simd.md`
for the declined-input protocol and `.kb/linalg-blas.md` for the flag whose shape this copies.

| backend | interceptor | kernels |
|---|---|---|
| interpreter (`prog.lisp --gpu`, native binary included) | `eval/LinalgGpu` (re-`defineFunction`) | `eval/LinalgGpuKernels` -> `am.ik.gpu` |
| JVM (`-o Prog.class --gpu`) | `codegen/jvm/JvmLinalgKernelCompiler` (call site) | `JvmGpuTemplate` -> the EMBEDDED `am.ik.gpu` |
| wasm-GC / `--no-gc` | out of scope, no FFM -- a hard error | -- |

**A `.wasm` output REFUSES rather than ignores** (`RontoLispCli.compileRecorded`). **`--gpu` is
value-less and `RontoLispCli.enableGpu` is `enableBlas` one layer up.** **Nothing may ask
`LinalgGpu.available()` on a path that did not pass the flag** -- it runs the probe (~26 ms cold),
which is the one way this flag is not like `--blas`.

**Fifty-seven `linalg:` members and one outside it.** By round trip: `linalg:dot` over two packed
rank-2 operands of the same width (hence `matmul` at rank 2 and `solve` transitively); `%la-matmul-nd`
and its transposed siblings `-ta` / `-tb`; the twelve element-wise `exp` `log` `tanh` `sin` `cos`
`tan` `asin` `acos` `atan` `sinh` `cosh` `erf`; the STRIDED tier -- `add` `sub` `mul` `div` `maximum`
`minimum` at a BROADCAST shape only, `sum` `amax` `amin` in their `:axis` form only, `transpose` in
its axes form only; and `%la-rng-fill`. Over a RESIDENT operand: the resident, index and copy tiers.
The FUSED tier: `linalg:softmax` and `log-softmax` in their `:axis` form over the last axis, plus
`%la-softmax-grad`, `%la-log-softmax-grad`, `%la-gelu`, `%la-gelu-grad`, `%la-layer-norm`,
`%la-layer-norm-grad`, `%la-layer-norm-affine`, `%la-layer-norm-affine-grad`, `%la-dropout-mask`,
`%la-scaled-masked-softmax` and its `-grad`. Outside `linalg:`: `vec:matvec`, installed by
`LinalgGpu.installVec`. **Nothing else is `defineFunction`ed**, and that is an assertion.

**The generator fill is the one member whose device result is byte-for-byte the CPU's at every
size**, and that is what let it in: the closed form `a^k s mod m` lets thread `i` jump to its own
state by square-and-multiply, then draw exactly as the sequential walk does, every arithmetic step an
`_rn` intrinsic so nvcc cannot contract `lo + span * u` into an FMA; `Gpu.rngAdvance` advances the END
state on the host by the same closed form.

### The chain order, and why the device goes on top

On the interpreter a chain is INSTALL ORDER, so where `LinalgGpu.install` sits in
`LispEvaluator.resolveFunction`'s lazy-load hook IS the decision. It goes LAST:
`device -> library gemm -> lane kernel -> scalar linalg.lisp defun`, and every prefix works the same
way. Three reasons: **`worth()` is probe-free and three orders of magnitude above `--blas`'s** (2^17
against 64), so the device turns down everything small before anything touches the driver; **where it
accepts it is at worst level with a threaded CPU BLAS and clearly ahead at f32**; and **a declined
member lands on the best CPU path the invocation asked for**, never back on the scalar defun.

**`--parallel` sits strictly BELOW the device decision on both backends.**

### The call site

`JvmLinalgKernelCompiler.compile` emits up to THREE attempts in the interpreter's install order:
`_gpuInit(); _blasInit(); _simdInit();` then each argument form evaluated ONCE into a temp, then
`gpuDot` / `blasDot` / `laDot` each falling through on `null` to the scalar defun. The temps are what
make a chain of any length safe: every decline branch RE-READS them
(`anArgumentFormIsEvaluatedExactlyOnceEvenWhenTheKernelDeclines`, in all three suites).

- **The device attempt is per MEMBER, not one hardcoded method**: `JvmLinalgGpu.kernelKey` maps each
  member to its `ops` key, where the key IS the bridge method name.
- **The extended (option-form) call sites carry a device rung too** -- the axis folds and the axes
  transpose are device members ONLY in that shape -- claimed when EITHER bridge has a kernel for it
  and emitted with the SAME `LinalgKernelCallLayout` the lane attempt uses. A call shape at which
  nothing would be attempted routes to `compileDefault`.
- **The emit gate (`usesGpu`) is `programUsesSymbol` over EVERY member**, not `--blas`'s gate on `dot`
  alone. A program that reaches no member embeds nothing, and **`--gpu` must NOT drag in the `--simd`
  bridge** -- a class that did would need `java --add-modules jdk.incubator.vector` to run.
- **`-Pweb`**: `LinalgGpu.available` / `description` / `install` / `installVec` are the only entry
  points into `LinalgGpuKernels`, which holds the only reference to `am.ik.gpu` from `eval`, so both
  bindings drop out behind the same four substitutions (`src/web/java/.../Target_LinalgGpu.java`). **A
  new public method on `LinalgGpu` that touches the kernels would break it, and only the Pages
  workflow's Web Image build would notice**; `./mvnw -Pweb compile` is the local check.

## The JVM backend: the whole library travels in the class

A GPU binding is ~1700 lines across several classes plus two kernel texts, and the parts a copy would
fork are exactly the parts that were expensive to get right. So `JvmGpuRuntimeBuilder` generalizes the
`--blas` template mechanism from one class to a CLOSURE of them plus data resources:

- every class file of `am.ik.gpu` is renamed by ONE prefix rule, `am/ik/gpu/` -> the generated
  program's own package plus `RontoLispGpu` (`Gpu` becomes `RontoLispGpuGpu`), because
  `Lookup.defineClass(byte[])` requires the defined class to share the lookup class's package.
- `JvmGpuTemplate` is renamed to `RontoLispGpuBridge` by the same pass, which lets it be WRITTEN
  against `am.ik.gpu` and type-checked by javac while resolving to the embedded copies at run time.
- each is base64'd into its own chunked string constant and `_gpuInit` runs one `defineClass` per
  blob. Definition order is free.

**BOTH kernel texts travel in every `--gpu` class whichever machine emitted it**; they cannot be
resources on the other side, so `_gpuInit` hands each to `Gpu.useKernels` / `useMetalKernels` before
anything can probe.

**What it is NOT.** The renamed classes are defined into the emitted class's own loader, so two `--gpu`
classes loaded by ONE classloader would collide on `defineClass` -- the reason the compiled-backend
tests give each program a fresh `URLClassLoader`.

### The offer is decided twice, and what pins the two

The library travels, but the DECISION to offer a shape to it does not: `eval/LinalgGpu` and
`codegen/jvm/JvmGpuTemplate` both sit ABOVE `am.ik.gpu`, so neither backend can correct a
disagreement. A shape one accepts and the other declines is a program that runs `java -jar` and
`-o out.class` down different paths with nothing failing.

**The pin is `codegen/jvm/GpuOfferDifferentialTest`**, and deliberately not thirteen per-helper
assertions. The two files share thirteen predicates (`batchStride`, `bcast`, `bcastShape`,
`bcastStrides`, `copyInto`, `foldAxis`, `map`, `resident`, `rowMajorStrides`, `sameShape`, `scale`,
`zip`, and `LinalgGpu.suffixLength` against `JvmGpuTemplate.softmaxMaskLength`), and the question is
asked from OUTSIDE both paths: one set of operands, each path's own call shape, and the two must agree
on accept versus decline and, where they accept, answer the same bits. The shapes are chosen at the
accept BOUNDARY, and a census assertion fails the run if the table did not both accept and decline.
No pair is a different predicate: four are word-for-word, and the rest differ only because a
`LispFloatArray` carries `dims()`/`storage()` where a compiled array carries a header, plus three
guards the bridge needs (`rank < 1`, `count < 1`, an overflow bound) that can only bite over a rank-0
or zero-extent operand no tier can make RESIDENT.

**Only the member-SET half runs on a GPU-less machine.** That half is device-free: every name the
compile path claims (`JvmLinalgGpu.qualifiedMembers()`) is bound to a sentinel and handed to
`LinalgGpu.install`, and a mismatch either way makes `install` throw with the member in the message.
The SHAPE half cannot be asked without a device, structurally.

## Tests

| what | where |
|---|---|
| the library, needs a DOUBLE-capable (CUDA) device | `am/ik/gpu/GpuTest` |
| the library, needs a METAL device | `am/ik/gpu/MetalGpuTest` |
| the library, must hold on EVERY machine -- both kernel texts included | `am/ik/gpu/GpuDeclineTest` |
| the native-image downcall registration, both drivers, every machine | `am/ik/gpu/NativeImageForeignConfigTest` |
| the interpreter's interceptor | `eval/LinalgGpuTest` (device), `eval/LinalgGpuDeclineTest` (every machine) |
| the compiled interceptor, both halves | `codegen/jvm/JvmLinalgGpuAccelCompilerTest` |
| the two paths' OFFER, differentially | `codegen/jvm/GpuOfferDifferentialTest` |
| the flag is value-less, the REPL pair, the `.wasm` refusal | `cli/CliOptionsTest`, `cli/RontoLispCliTest` |

**The dead-flag guard is the load-bearing one**, as for `--blas`: every numeric assertion would pass
on the scalar defun, so `#'linalg:dot` printing `#<function LINALG:DOT>` under the flag and `#<lambda>`
without it is what fails when the flag is DEAD -- one assertion per accelerated member, plus the
complementary list of members that must still be `#<lambda>`. On the compiled side the guard is the
bridge NAME in the class bytes.

- **`GpuDeclineTest` is the half a CI runner actually runs** -- every machine this project has is
  GPU-less -- so it must never regress. It pins the probe answering without throwing, every decline
  condition declining rather than throwing, the status table total with only the context-destroying
  statuses sticky, the PTX being the artifact the loader expects with its regeneration command
  attached, the MSL naming its kernels and holding no `double` outside comments, the op-code mirrors
  matching, and -- the one that matters -- **that an op code the library does not name DECLINES
  rather than quietly computing something**. **It hands the library the REAL checked-in text and no
  test anywhere may hand it anything else**: the override is process-wide and read at probe time.
- **`JvmGpuRuntimeBuilder.embeddedGpuClasses()` is pinned against the class files the build actually
  produced** -- the guard that a class added to `am.ik.gpu` is added to the list that travels.
- **The interceptor suites derive their shapes and their width from the device in force**, through the
  test-scope `am.ik.gpu.GpuThresholds` shim: `SIDE` is the smallest accepted square (64 on CUDA, 208
  on Metal), `MAP_N` twice the element threshold, `TYPE` is `single-float` where there is no double.
- **The tests that assert on device memory hold a `@ResourceLock`.** Five ask the POOL; two still ask
  `cuMemGetInfo` against the 1.5 GB bound. Every leak run is sized so a real leak is 2-8x its bound.
- **A test that asserts an exact `residentBytes()` must KEEP ITS ARRAYS REACHABLE, and a process-wide
  `dirtyCount()`/`backingCount()` diff around one call is not that call's own effect** (both in
  `.kb/test-execution.md`). `DeviceResidency.dirty(Object)`/`.backed(Object)` (`GpuThresholds.isDirty`
  /`.isBacked`) exist for exactly that.
- **Exact-input operands must be exact IN THE FOLD too** -- a 64-long sum of products of 1..4096 is
  not, at f32, because the defun accumulates in f64 (`.kb/linalg-simd.md`'s reduction contract).

### The vacuity sweeps, and the rules they left

**A test whose shape does not clear the threshold that gates the mechanism it asserts on runs nothing,
and passes** (`.kb/test-execution.md`). Both backends were swept; they found DIFFERENT tests vacuous,
because the thresholds differ, so neither answers for the other. Every finding was established by
MUTATION. The rules:

- **`Gpu.worth(n, m, p)` applies `POOLED_MIN_WORK` (131072), not the `Probe.MIN_WORK` the offer
  applies (4194304 on the M4 Max)** -- so "the shape is above the threshold" can be checked, believed,
  and wrong. `Gpu.multiply` is the only authority and `GpuThresholds.minWork()` the number to size
  against.
- **Size an operand off the FINITE thresholds only.** `GpuOfferDifferentialTest`'s `BIG` was
  `2 * max(map, strided, fold, fused)` and Metal's `fold` is `Long.MAX_VALUE`, so `BIG` wrapped to
  `-2` -- RED on Metal and invisible because every CI runner is GPU-less. Fixing the twin
  (`JvmLinalgGpuAccelCompilerTest.theFusedTierRunsOnTheCompiledBackendAndLandsOnTheChainsBits`)
  exposed a real divergence: `linalg:log-softmax` against its chain answers NIL, so that line is now a
  bound rather than bit-identity.
- **Size a decline enumeration off the threshold of the tier it enumerates.**
  `GpuTest.everyStridedDeclineConditionStillDeclinesWithADevicePresent`'s three FOLD conditions were
  sized off `stridedMinElements()` while the fold is gated by its own floor; now
  `max(stridedMinElements(), foldMinElements())`.
- **A `...StillDeclinesWithADevicePresent` test now opens by asserting the same shape ACCEPTED, over
  the baseline's OWN arrays** -- its own because an accepted call leaves its operand resident. Six
  tests carry one. **`eval/LinalgGpuDeclineTest`'s and `GpuDeclineTest`'s shapes are fixed and must
  stay fixed**: they are the half a GPU-less runner executes.
- **A resident-only member cannot be pinned with a fresh operand.**
  `theClipNormFoldsInBlocksOnTheDeviceCloseToTheSequentialSumAndReproducibly` reached the device on
  NEITHER backend; the gradient is now built through a broadcast add. The fused tier's ROW-COUNT floor
  (`Gpu#offeredRows`) was pinned NOWHERE and is now pinned with a total ABOVE the threshold laid out
  in too few rows (128 x 2048 against 256 x 1024), the 256-row form asserted ACCEPTED first.
- **`eval/LinalgGpuDeclineTest`'s shapes are fixed and must stay fixed**: it is the half a GPU-less
  runner executes. Same for `GpuDeclineTest`, whose fixed shapes make seven of its nine enumerations
  free device-PRESENT pins on the GB10.

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
  WORKING GPU. Two things stand against it now: the drivers let a binding failure THROW; and
  `NativeImageForeignConfigTest` binds both drivers against a lookup that finds everything (no device
  needed) and asserts every shape they ask the linker for has an entry.

Generate them with the tracing agent over a program that opens the binding and runs a member; merely
constructing a driver registers every shape. **The type names must be the agent's own** (`jlong`,
`jint`, `jboolean`): the un-prefixed aliases parse, but `boolean` does NOT. **A per-entry `"comment"`
key is rejected by the schema**, which is why the signature-to-entry-point mapping lives in the
file's top-level `comment` array.

## What is deliberately NOT here

Each is a measured decline, and each needs this file's numbers before it is revisited.

- **No element-wise member whose scalar cost is one machine instruction, AS A ROUND TRIP** -- `sqrt`,
  `abs`, `negative`, `sign` and the binary ops at an equal shape. They ARE members over a RESIDENT
  operand, which is not a reversal but the case the refusal's measurement never had. Re-run
  `ElementwiseCrossover.java` plus `elementwise-baseline.lisp` before offering any as a round trip.
- **No axis fold on METAL as a round trip.** The amax/amin half is the one to revisit first if that
  backend's floor ever drops; the sum half cannot come back while `%la-fold-axis` accumulates in
  double.
- **No index tier, no clip norm and no fused layer-norm AFFINE on METAL** -- the kernels were never
  written for the first two; the affine pair was written, measured and not kept.
- **Nothing of `vec:` but `vec:matvec`.** `vec:matvec-into` writes a CALLER's array; `vec:dot` loses
  to the lane kernel at every size. The first sight of a big matrix used ONCE is left on the table
  deliberately.
- **No zero-copy route, and no staged UPLOAD.** Measure with FRESH arrays before touching either half.
- **The per-call cost of an FFM downcall inside a native image is still unexplained**; the generic
  `MethodHandle` invoker under every downcall is the suspect.
- **No per-device collection policy.** It becomes a `GpuDevice` question only if the two backends'
  collection requests ever want different answers.
