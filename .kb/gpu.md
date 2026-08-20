# `am.ik.gpu`: a matrix product on the GPU, or a decline

The foundation of `--gpu` (todo-123 phase 1), landed on its own: a language-independent
library that takes a matrix product and either runs it on an NVIDIA GPU or answers `null`.
There is no flag yet, no `linalg:` interception and no user-facing documentation -- this
file is the whole record of the library, and the interceptors that will sit on top of it
are phase 1B (interpreter) and phase 2 (JVM).

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
| `am.ik.gpu.Gpu` | the whole public surface: `available`, `description`, `worth`, two `multiply` overloads |
| `am.ik.gpu.CudaGemm` | the probe, the context/module lifetime, and the per-call product |
| `am.ik.gpu.CudaDriver` | the FFM binding: `libcuda.so.1` and 24 downcall handles |
| `am.ik.gpu.CuResult` | every CUDA 13 status code, and which of them leave the context dead |
| `src/main/resources/am/ik/gpu/gemm.cu` / `gemm.ptx` | the kernels, source and checked-in artifact |

## The API

```java
static boolean available()                       // does this machine have one
static String  description()                     // what was found, or why nothing was
static boolean worth(long n, long m, long p)     // is this product big enough to offer
static double[] multiply(double[] a, int offsetA, double[] b, int offsetB, int n, int m, int p)
static float[]  multiply(float[]  a, int offsetA, float[]  b, int offsetB, int n, int m, int p)
```

Row-major `n x m` by `m x p`, a fresh `n * p` array back or `null`. Two shapes of the same
decision are deliberate: `worth` so a caller can refuse before it unwraps its operands,
and `multiply` re-asking anyway so it cannot be bypassed.

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
- Nothing else is in the PTX yet. The batched rank-3 product and the element-wise tier are
  later phases, and the PTX regenerates then.

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
  `aRunOfSuccessfulProductsFreesEveryBufferItAllocates` runs 500 products that WORK, and
  `aDeclinedProductCostsTheDeviceNothing` runs twelve that FAIL, which is the path the
  first one never enters and the one that was wrong. Both assertions are two-sided --
  free memory that GREW would mean the test is measuring the rest of the machine.
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

The tiled kernel walks its reduction in 16-wide tiles, so an accelerated product is CLOSE
to a scalar row-by-column product, not equal to it. Over inputs that are exact at the
operand width -- small integers, powers of two -- the results still match EXACTLY, which is
what `GpuTest` asserts; over inexact ones they differ. Measured over random zero-mean
inputs (dyadic test data round-trips exactly and hides the whole question), as a fraction
of the largest cell of the f64 oracle:

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

## Native image

Two build inputs, both already in
`src/main/resources/META-INF/native-image/am.ik.rontolisp/rontolisp/`:

- **`resource-config.json`**: `am/ik/gpu/gemm\.ptx`, conditional on `am.ik.gpu.CudaGemm`,
  beside the `--simd` and `--blas` template entries. Without it the binary probes, finds a
  GPU, and then fails to find its own kernels.
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

## Tests

Mirrors `--blas`'s split exactly.

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
is total, that only the context-destroying statuses are sticky, and that the PTX is the
artifact the loader expects with its regeneration command still attached.

Everything here is skipped on a machine without a GPU, which is every CI runner this
project has -- so `GpuDeclineTest` is the half that actually runs there, and it is the half
that must never regress.

## What is deliberately NOT here

No `linalg:` interception, no `--gpu` flag, no CLI wiring, no interpreter or JVM
interceptor, no residency, no batched rank-3 product, no element-wise tier, no Metal. Those
are todo-123's phases 1B through 5, and each of them needs this file's numbers before it
starts.
