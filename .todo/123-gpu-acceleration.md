# `--gpu`: a second orthogonal acceleration flag, over the same call sites as `--simd`

**Status:** spiked 2026-08-20 on an NVIDIA GB10 and it works; spiked the same day on an
Apple M4 Max, where it works too but says something different. This file replaces the
2026-07-13 draft, which was written before `torch:` existed and before anything had
been measured on real hardware. Difficulty: High.

The 2026-07-13 draft asked one question -- "is a GPU backend cheap to reach for, given
the `--simd` interception seam?" -- and deferred everything until someone bound one
kernel through Panama and measured it. That spike has now been run. **The answer is
yes, and it is cheaper than the draft assumed**: the two problems it called "the crux"
(device residency, and how to get a GPU into a project that ships no dependencies)
both came out better than feared.

## The verdict, up front

1. **Pure FFM, zero dependencies, driver only.** The whole CUDA path is
   `SymbolLookup.libraryLookup("libcuda.so.1")` plus ~20 `downcallHandle`s. No JNI, no
   bundled native shim, no Java library, and -- the decisive result -- **no CUDA
   toolkit on the user's machine**: a PTX text generated at BUILD time and carried as a
   resource is JIT-compiled by the driver onto hardware six generations newer
   (`compute_75` PTX loaded onto `sm_121` in 25.9 ms). `libcuda.so.1` ships with the
   NVIDIA driver, so "has a working GPU" is the entire runtime requirement. This is
   what makes `--gpu` compatible with the no-external-dependencies rule; it is not a
   compromise on it.
2. **It survives native-image**, which was the draft's stated fear (todo-102's
   `-H:+VectorAPISupport` vs `-H:+SharedArenaSupport` fight). A GraalVM 25 binary built
   with `-H:+VectorAPISupport` AND the CUDA downcalls ran the kernel correctly. There is
   no shared-arena conflict because nothing here needs `Arena.ofShared()` --
   confined and global arenas are enough. The only new build input is a `foreign`
   section in `reachability-metadata.json`, which the tracing agent generates.
3. **Residency is a 2-4x optimization, not a precondition.** The draft assumed a
   per-call intercept that copies host->device->host would be a LOSS at realistic sizes
   and that an array which LIVES on the device was mandatory before anything could pay.
   Measured, it is not: a plain per-call intercept with both copies already beats the
   fastest CPU path rontolisp has (`--simd` on the JVM) from about n=64 upward, and by
   24x (f64) / 34x (f32) at n=512 -- both figures re-measured after todo-469. Residency
   then buys a further 2.0-3.7x. That
   reorders the plan: phase 1 is the `--simd` protocol again, copies and all, and
   residency is phase 3.
4. **The honest limits.** `--gpu` reaches **two of the four backends** (interpreter,
   incl. the native binary, and JVM). WASM has no FFM and is out, so unlike `--simd`
   this flag cannot claim near-parity. And the win is an **f32** win: on this device
   fp64 runs at 1/44 of fp32 throughput, while `linalg`'s default width is
   double-float.
5. **The same three answers hold on Apple, and a fourth one lands on top.** Metal is
   reachable by pure FFM too (`objc_msgSend`, no Swift shim), it survives native-image,
   and its runtime MSL compiler needs no toolchain at all -- so sections 1 and 2 carry
   over unchanged. What does NOT carry over is the kernel: MSL has no `double`, the
   per-call floor is 5x higher, and Apple ships two tuned libraries in the OS that beat
   a hand-written kernel outright. The Metal section below is the whole story, and it
   is the section that changes what phases 1-4 should build on that platform.

## What was measured

Spike sources: **`.todo/123-gpu-acceleration/`**, kept beside this file so every number
below can be re-derived on other hardware. Throwaway probes, not project code -- outside
`src/`, not in the reactor, not formatted, nothing builds them; each is a single-class
JDK source-launcher program with no dependency beyond the FFM API. That directory's
`README.md` says which probe answers which question, how to run it (including the
native-image leg) and what it printed verbatim.
Machine: NVIDIA GB10 (Grace Blackwell, `sm_121`, 48 SMs, unified addressing + managed
memory), aarch64, driver 580.173.02 / CUDA 13.0, Oracle GraalVM 25.0.4.

### One `linalg:matmul` call, against the fastest thing we have today

`--simd` on the JVM, warm, 20 reps, against the CUDA path including BOTH copies
(heap -> native segment -> device, kernel, device -> native -> heap). Same square
shapes, ms per call:

| n | `--simd` f64 | `--simd` f32 | gpu f64 w/copy | gpu f64 kernel | gpu f32 w/copy | gpu f32 kernel |
| --- | --- | --- | --- | --- | --- | --- |
| 32 | 0.100 | 0.100 | ~0.017 | -- | -- | -- |
| 64 | 0.450 | 0.400 | **0.043** | 0.025 | 0.036 | 0.020 |
| 128 | 1.400 | 0.850 | **0.072** | 0.029 | 0.037 | 0.016 |
| 256 | 2.800 | 1.450 | **0.198** | 0.088 | 0.088 | 0.026 |
| 512 | 21.400 | 10.900 | **0.906** | 0.576 | 0.320 | 0.120 |
| 1024 | -- | -- | 5.720 | 4.432 | 1.625 | 0.859 |
| 2048 | -- | -- | 40.541 | 35.238 | 9.360 | 6.740 |

For scale, the plain Java triple loop (the shape of the scalar `%la-matmul` defun,
JIT-warm only at the small end): n=512 142 ms, n=1024 1246 ms, n=2048 16.3 s.

**The `--simd` columns are POST-todo-469**, re-measured on the GB10 after `5a3e8f16` gave
the f32 matmul kernel its lanes; the GPU columns are unchanged, since nothing about them
depends on rontolisp. That landing moved the f32 column by 3.7x at n=512 (39.8 -> 10.9 ms)
and inverted the widths, so the two things this table used to say have to be restated:

- The f32 column is still where the GPU is, but no longer because f32 costs the CPU path
  more. `--simd` is now 2x FASTER at f32 than at f64, exactly as a narrower width should
  be; the GPU's f32 advantage over it at n=512 is **34x**, not the 124x the pre-469
  numbers implied. The CUDA conclusion is unaffected -- 34x is not a close call -- but
  quote the new figure.
- The crossover is still far lower than the draft guessed, and for the same reason: what
  `--gpu` has to beat at small n is not raw CPU FLOPs but rontolisp's own per-call
  overhead. At n=64 the GPU is 11x the CPU path at f32 and the floor still dominates.

### The fixed cost of an intercepted call

| | |
| --- | --- |
| `cuLaunchKernel` + `cuCtxSynchronize`, empty-ish kernel | **8.1 us** |
| `cuLaunchKernel` alone (async, no sync) | 3.7 us |
| full round trip, 2 HtoD + kernel + DtoH + sync, 8x8 @ 8x8 | 18.0 us |
| same, 32x32 @ 32x32 | 19.7 us |
| same, 128x128 @ 128x128 | 56.0 us |

So ~16-18 us is the floor per intercepted call and it is flat until the data gets big
enough to matter. That number is the size threshold's basis: anything whose scalar cost
is below it must DECLINE, and the decline protocol gives that for free.

### The batched rank-3 product -- the shape `--simd` cannot reach at all

This is todo-467's member (`linalg::%la-matmul-nd`), which is "essentially the whole
forward and backward pass" of a transformer and which `--simd` today does not intercept
and (per todo-467's measurements) makes 11% SLOWER on wasm-GC. On the GPU a batch axis
is just `blockIdx.z`:

| shape (b*h, n, d) | gpu f32 | throughput |
| --- | --- | --- |
| 24 x (64 x 32) | 0.025 ms | 249 GFLOP/s |
| 48 x (256 x 64) | 0.187 ms | 2152 GFLOP/s |
| 192 x (512 x 64) | 2.679 ms | 2405 GFLOP/s |

(The Java f64 reference for the same FLOPs -- 27 ms / 350 ms / 2778 ms -- is an
unwarmed triple loop, so treat it as an order of magnitude, not a tuned CPU number.)

### The residency question, answered

The draft's crux. Same 5-op chain (`matmul`, `add`, `tanh`, `matmul`, `add`) at f32,
run two ways: everything resident (one upload, five kernels, one download) versus a
per-op intercept that uploads its operands and downloads its result every time:

| n | resident | per-op round trip | penalty |
| --- | --- | --- | --- |
| 128 | 0.051 ms | 0.192 ms | 3.7x |
| 512 | 0.369 ms | 1.012 ms | 2.7x |
| 1024 | 2.218 ms | 4.441 ms | 2.0x |

A 2-4x penalty, not the catastrophe the draft predicted -- and the per-op column is
still an order of magnitude under `--simd`. Two caveats before generalizing it: this
box's host and device memory are coherent over NVLink-C2C, so a discrete PCIe card will
show a worse per-op column; and the penalty grows as the ops get cheaper relative to
their operands (it is worst at n=128, not best).

The memory routes are close enough to each other that none of them is a design
decision. n=1024 f64 gemm, end to end:

| route | |
| --- | --- |
| `cuMemAlloc` + explicit `cuMemcpyHtoD`/`DtoH` (two copies) | 6.028 ms |
| `cuMemAllocManaged`, CPU stores straight into it | 5.546 ms |
| pinned host + `CU_MEMHOSTREGISTER_DEVICEMAP` (GPU reads host memory) | 5.450 ms |
| ... same buffers already resident, kernel only | 4.441 ms |

The heap `double[]` -> native copy is unavoidable in every row: FFM cannot hand a Java
heap array to a native call, and `linalg`'s arrays are `double[]`/`float[]`. Managed
memory removes the second copy, not the first.

### The f64 penalty, and how far a hand-written kernel is from a tuned one

**cuBLAS appears here as a measuring stick, not as a candidate dependency.** It is
NVIDIA's tuned BLAS, it ships only inside the CUDA toolkit (`libcublas.so.13`, found on
the spike machine at `/usr/local/cuda/lib64/`), and requiring it would undo the whole
point of section 1 -- so the design does not use it. It was bound with FFM for one
afternoon to answer two questions the built-in PTX cannot answer about itself: *how
much of this hardware is a naive kernel leaving on the floor*, and *what does this
hardware actually do at each width*.

| n, width | cuBLAS | tiled PTX kernel | ratio |
| --- | --- | --- | --- |
| 1024 f32 | 0.127 ms (16891 GFLOP/s) | 0.860 ms | 6.8x |
| 2048 f32 | 0.918 ms (18720 GFLOP/s) | 6.708 ms | 7.3x |
| 1024 f64 | 5.116 ms (420 GFLOP/s) | 4.432 ms | 0.9x |
| 2048 f64 | 40.866 ms (420 GFLOP/s) | 35.183 ms | 0.9x |

Two separate readings, and they answer different questions:

- **Down the cuBLAS column: the hardware's own width penalty.** Same library, same
  shape, only the element width differs -- 18.7 TFLOP/s at f32 against 420 GFLOP/s at
  f64, a **44x** gap. That is an architectural property of this class of device (fp64
  units at ~1/64 of the fp32 rate), not a software artifact, and it is the dominant
  fact for a `linalg` whose default element type is double-float: **a `--gpu` that only
  ever sees `#d` arrays is throwing away 44/45ths of the device.** Hence "this is an f32
  feature": either the flag drives programs toward `:element-type 'single-float`, or
  most of the win never arrives.
  Ruled out as an explanation: the f32 row is genuine FP32, not TF32 tensor cores
  silently truncating the mantissa. `cublasGetMathMode` returns `CUBLAS_DEFAULT_MATH`
  (0), and a diagonal SGEMM carrying `1 + 2^-20` -- representable in fp32's 24-bit
  mantissa, NOT in tf32's 11-bit one -- came back bit-exact (`3f800008`). So the two
  numbers are comparable and the 44x is real (`Tf32Check.java`).
- **Across each row: how good the naive kernel is.** At f64 a plain 16x16 tiled kernel
  already MATCHES cuBLAS (0.9x -- both are pinned by the same scarce fp64 units, so
  there is nothing for tuning to win). At f32 it leaves 7x on the table, because that is
  where blocking, vectorized loads and tensor cores actually pay.

### So is cuBLAS worth having, where it exists?

The 6.8x / 7.3x above is a KERNEL ratio, which is the wrong question for phase 1 -- there
the copies are on the clock too. Both kernels, both phases, both widths, ms per call
(`CublasEndToEnd.java`):

| width, n | ours + copy | cuBLAS + copy | | ours resident | cuBLAS resident | |
| --- | --- | --- | --- | --- | --- | --- |
| f32 256 | 0.080 | 0.067 | 1.2x | 0.030 | 0.017 | 1.8x |
| f32 512 | 0.269 | 0.184 | 1.5x | 0.121 | 0.034 | 3.6x |
| f32 1024 | 1.404 | 0.672 | 2.1x | 0.861 | 0.127 | 6.8x |
| f32 2048 | 8.799 | 2.970 | 3.0x | 6.752 | 0.927 | 7.3x |
| f64 256 | 0.167 | 0.206 | **0.8x** | 0.087 | 0.125 | **0.7x** |
| f64 512 | 0.858 | 0.990 | **0.9x** | 0.576 | 0.707 | **0.8x** |
| f64 1024 | 5.433 | 6.143 | **0.9x** | 4.433 | 5.116 | **0.9x** |
| f64 2048 | 39.579 | 44.922 | **0.9x** | 35.243 | 40.875 | **0.9x** |

**The answer is no, and it is not close.**

- **At f64 -- `linalg`'s DEFAULT width -- cuBLAS is a regression**, 10-25% SLOWER than
  the naive tiled kernel at every size. Nothing about DGEMM is tunable here: both are
  pinned by the same scarce fp64 units, and cuBLAS's heuristics and setup are pure
  overhead on top. So for the width most rontolisp programs actually use, the dependency
  buys negative performance.
- **At f32 under phase 1 the 7x collapses to 1.2-3.0x**, because the copies it does not
  eliminate become the bulk of the call. The full 7x only exists in the phase-3 world
  where data is already resident AND n >= 1024.
- **The price is 660 MB, but only where it is not already paid.** `libcublas.so.13` is
  59 MB and links `libcublasLt.so.13` at 601 MB. Requiring that is the whole
  CUDA-toolkit-on-the-user's-machine problem reintroduced -- for a factor that is
  negative at the default width. Two qualifications, since the first draft of this bullet
  overstated it: cuBLAS is NOT part of the driver (on the spike machine it comes from
  `libcublas-13-0`, pulled by `cuda-libraries-13-0`, i.e. the toolkit's runtime metapackage
  and not the driver package), so "has a working GPU" still does not imply it; but on any
  machine that has the CUDA stack at all -- every DGX, every ML dev box -- it is already
  installed AND already on the `ldconfig` path, so a bare `dlopen("libcublas.so.13")`
  finds it and the marginal cost there is zero. The size argument is therefore an argument
  against REQUIRING it, not against using it opportunistically.

So: the built-in PTX is not a stopgap, it is the answer. Closing the f32 gap is a
self-contained kernel-tuning exercise (register tiling, vectorized loads, bigger tiles)
that needs no dependency and recovers most of it. In ROI order the real wins are
**single-float adoption (44x) > residency (2-4x) > kernel tuning (up to 7x, f32 only)**,
and cuBLAS is a strictly worse way to buy the last of those. Revisit only if phase 3
lands, f32 workloads dominate in practice, and someone still wants the last 2-3x on a
machine that already has the toolkit -- as an opportunistic `dlopen`, never a
requirement. That is the same shape `--blas` settled on for the CPU (`.kb/linalg-blas.md`): a tuned library is
RECOMMENDED and never required, so nothing is bundled or downloaded, a machine without one
runs the same programs to the same output, and the only rule that binds is that we may not
REQUIRE what the OS or the driver does not already provide.

### And the other thing that competes with `--gpu` at f64: 20 CPU cores

Measured after the fact, with OpenBLAS 0.3.26 installed on this same machine (it is not
there by default -- stock, the only BLAS present is the netlib reference, which is 1.6x
SLOWER than `--simd`). Threaded across the GB10's 20 Grace cores, ms per call:

| n | `--gpu` f64 w/copy | OpenBLAS f64 | | `--gpu` f32 w/copy | OpenBLAS f32 |
| --- | --- | --- | --- | --- | --- |
| 512 | 0.906 | 1.073 | 1.2x | 0.320 | 0.514 | 1.6x |
| 1024 | 5.720 | 5.942 | 1.04x | 1.625 | 2.943 | 1.8x |
| 2048 | 40.541 | 43.921 | 1.08x | 9.360 | 21.492 | 2.3x |

**At f64 it is a tie.** The GB10's fp64 is weak enough -- 420 GFLOP/s even from cuBLAS,
one forty-fourth of its f32 -- that 20 Grace cores at 391 GFLOP/s catch a whole GPU. So on
this class of machine `--gpu`'s f64 case is not "faster than the CPU"; it is "the same as
a CPU library the user may already have installed, with a device dependency attached". At
f32 the GPU keeps a real but modest phase-1 lead of 1.6-2.3x, which residency and kernel
tuning would widen.

This is the same conclusion Apple reached by a completely different route -- there MSL has
no double at all, so f64 cannot even be attempted -- and it is the strongest argument yet
for **phase 0**: `--gpu`'s reason to exist is f32, residency and the batched rank-3 shape,
and a `--gpu` that mostly sees `#d` arrays is competing with the CPU rather than beating
it. The CPU-BLAS side is `--blas` (`.kb/linalg-blas.md`); what belongs here is that the f64 half of `--gpu` has
a credible CPU competitor on both platforms, and should stop being quoted as the headline.

The f64 tiled kernel is NOT bit-identical to the scalar defun (max abs difference 1.5
to 2.7 on the spike's inputs): the tile walk reorders the reduction. That is expected
and is the precision contract below.

## The design

### Where it plugs in: the same seam, a second flag

`--gpu` is another flag over the SAME `linalg:` call sites `--simd` already intercepts
(`.kb/linalg-simd.md`), obeying the same two properties, which is why the whole
`torch:` package -- and therefore `examples/llm-from-scratch/`, `examples/ml/`, every
future `torch:` program -- gets it **without one line of Lisp changing**:

1. **The scalar `linalg.lisp` defun stays the oracle and is never rewritten.** A kernel
   is a PARTIAL function returning the null sentinel for anything it declines, and the
   call site then runs the defun. Boxed arrays, mixed widths, scalar operands, broadcast
   mismatches, error messages -- all stay in the library, none is duplicated. **The size
   threshold falls out of this for free**: an operand below the ~16 us floor simply
   declines and runs on the CPU.
2. **The flag is orthogonal.** A build without it is byte-identical to one that never
   knew it. `--gpu` never appears in `ci-spec.yaml`.

`torch:` is the motivating consumer but NOT an interception point. `.kb/torch.md`'s rule
holds -- torch never reimplements a kernel, it wraps one and adds its adjoint -- so
accelerating `linalg` accelerates both the forward and the backward pass, and the tape,
`torch:backward` and every adjoint stay untouched.

### Composition with `--simd`

`--gpu` wins where it ACCEPTS; `--simd` picks up everything it declines. That is the
draft's suggested rule and the measurements support it: the two flags never contend for
the same call because a declined GPU kernel falls through to the same call site
`--simd` would have taken. Both flags together must be a supported combination, and the
decline path must be the SIMD path, not the scalar one, when both are on.

### The kernel source route: PTX in the resources

Generate the PTX at build time with `nvcc -arch=compute_75 -ptx` (or NVRTC) and check
the resulting text in as a resource. At runtime `cuModuleLoadData` hands it to the
driver, which JITs it for whatever card is present -- 25.9 ms cold, and **1.3 ms** on
every later run, because the driver keeps its own on-disk compute cache
(`~/.nv/ComputeCache`) and the resource is a fixed text. So the load cost is a startup
non-issue and needs no `cuModuleLoadDataEx` cache plumbing of our own. Consequences:

- **Runtime requirement is `libcuda.so.1` alone.** No toolkit, no `libnvrtc`, no
  `libcudart`, no `libcublas`.
- **`compute_75` is the floor** (Turing, 2018). CUDA 13's NVRTC/nvcc refuse anything
  older, so that is not a choice we get to make. Pre-Turing cards decline to the CPU.
- The `.ptx` is generated text checked into the repo. Regenerating it needs a toolkit on
  the DEVELOPER's machine, not the user's; pin it with a test that the checked-in text
  loads and produces the reference values.

### Package placement

A new **`am.ik.gpu`** library package, under the same rule CLAUDE.md already states for
`am.ik.jvm` / `am.ik.wasm` / `am.ik.wit`: **language-independent, imports no rontolisp
package and no external dependency**. It owns the FFM bindings, device/context/module
lifecycle, buffer allocation, kernel launch, and the availability probe. Then, mirroring
the `--simd` trio exactly:

| backend | interceptor | kernels |
| --- | --- | --- |
| interpreter (`prog.lisp --gpu`) | `eval/LinalgGpu` (re-`defineFunction`) | `eval/LinalgGpuKernels` -> `am.ik.gpu` |
| JVM (`-o Prog.class --gpu`) | `codegen/jvm/JvmLinalgGpuCompiler` (call site) | an embedded bridge template |
| wasm-GC / `--no-gc` | **out of scope** -- no FFM | -- |

The interceptors are platform-agnostic; `am.ik.gpu` is where the platform split lives, and
it has two of them (CUDA driver API on Linux/Windows, Metal + MPS through `objc_msgSend` on
macOS) behind one availability probe that must answer "no device" without throwing on
either. Neither half is a dependency: `libcuda.so.1` comes with the NVIDIA driver, and
`Metal.framework` / `MetalPerformanceShaders.framework` come with macOS.

Dependency direction stays legal: `eval -> am.ik.gpu`, `codegen.jvm -> am.ik.gpu`, and
`am.ik.gpu -> nothing`.

### The JVM backend's embedded bridge is the one genuinely awkward piece

`--simd` base64-embeds a single template class (`JvmSimdVectorTemplate`) into the
emitted `.class` so that `java Prog` runs standalone. `--gpu` needs the same, and
`.kb/template-class-embedding.md`'s demerits all apply harder: the template must be ONE
class with no nested classes or records and no rontolisp imports (the FFM bindings fit
that, but only if written as flat statics), it carries the PTX text as chunked string
constants on top of its own bytecode, and it must be registered for the native binary.
Weigh the alternative before starting: emit a call into a `--gpu`-only support jar and
accept that `-o Prog.class --gpu` produces output that is no longer standalone. That is
a real departure from how every other flag behaves, so the default assumption is the
template -- but decide it deliberately, in phase 2, with the blob size measured.

### Metal: spiked 2026-08-20 on an Apple M4 Max

The 2026-07-13 sketch has now been run. Machine: Apple M4 Max (40 GPU cores, Metal 4,
unified memory), macOS 26.3.1, Oracle GraalVM 25.0.3, **no Xcode installed**. Probes are
the `Mtl*` files in `.todo/123-gpu-acceleration/`; the README there has every number
verbatim. Three of the sketch's four claims survived, one was wrong in our favour, and the
measurements then found two things the sketch had no way to anticipate.

**What the sketch got right.**

- **Pure FFM reaches Metal, and the Swift shim really is avoidable.** `Mtl.java` is 11
  distinct `objc_msgSend` signatures plus `MTLCreateSystemDefaultDevice`, and that is the
  whole binding -- device, runtime compile, pipelines, buffers, encoders, dispatch. No
  bundled dylib, no toolchain, no dependency, so section 1 of the verdict holds on Apple
  exactly as it does on CUDA. Two mechanical rules make it work and both bite immediately
  if broken: call `objc_msgSend` through a NON-variadic descriptor matching the selector
  (Apple's own arm64 rule), and give any selector returning `MTLSize` the 24-byte struct
  descriptor -- calling `maxThreadsPerThreadgroup` as a long is an instant SIGBUS.
- **Runtime compilation is better than the PTX story, not merely equal to it.**
  `newLibraryWithSource:options:error:` compiled MSL on a machine with no Xcode and no
  `xcrun metal`, because the compiler is in the OS. 32 ms the first time ever, **2.3-3.0
  ms on every later process** (the OS caches across processes, the way the NVIDIA driver
  caches PTX), 0.1 ms for the same source twice in one process. So there is no build-time
  artifact to generate, check in, or pin to a virtual architecture -- the MSL text is just
  a string constant. The real startup cost is `MTLCreateSystemDefaultDevice` at 12-15 ms,
  which is what the availability probe pays.
- **It survives native-image.** `MtlNiProbe` built with `-H:+VectorAPISupport` and ran the
  kernel with the same result as the JVM, so Metal does not re-enter todo-102's
  `VectorAPISupport` / `SharedArenaSupport` fight either. The recipe is identical to the
  CUDA one; the agent-generated `foreign.downcalls` just gains a two-`MTLSize`-by-value
  entry for `dispatchThreadgroups:threadsPerThreadgroup:`.

**What was wrong: `--gpu` on Apple is f32 by LANGUAGE, not by economics.**

MSL rejects `double` outright -- `error: 'double' is not supported in Metal` -- while
`half`, `bfloat` and 64-bit int all compile. On CUDA, fp64 is 44x slower than fp32 and the
argument for phase 0 is that a `--gpu` seeing only `#d` arrays wastes 44/45ths of the
device. On Apple there is no fp64 path to waste: an `#d` array can only DECLINE. That
promotes phase 0 from "do this so the measurements are honest" to "do this or the flag is
inert on Apple Silicon" -- and phase 0's own prerequisite landed on 2026-08-20, so nothing
blocks it now. It also means the decline protocol -- already load-bearing -- is the only
thing standing between a double-float `linalg` program and a compile error.

**What the measurements added: the floor is per COMMAND BUFFER.**

| | |
| --- | --- |
| encode a dispatch (no commit) | 4.0 us |
| encode + `commit` + `waitUntilCompleted` | 81.1 us |
| encode + `commit` + spin on `[cb status]` | 83.1 us |
| full round trip, heap -> buffer -> kernel -> heap, 8x8 @ 8x8 | 105.0 us |
| same, 32x32 @ 32x32 | 85.1 us |
| same, 128x128 @ 128x128 | 87.5 us |

~85 us, flat with size, **five times CUDA's 16-18 us**. Spinning on the status instead of
blocking changes nothing, so there is no cheaper wait to go find: that is the round trip.
But the cost is paid per command buffer, not per dispatch --

| dispatches in ONE command buffer | 1 | 2 | 5 | 10 | 50 |
| --- | --- | --- | --- | --- | --- |
| us per dispatch | 98.8 | 52.2 | 24.5 | 16.9 | 8.8 |

-- so batching is worth up to 10x and is the same mechanism residency needs. This is the
one structural difference in the plan: on CUDA, residency is a memory optimization worth
2-4x; on Apple it is ALSO how the submission floor gets amortized, and the residency probe
separates the two:

| n | resident, 1 cmdbuf | resident, 5 cmdbufs | per-op round trip |
| --- | --- | --- | --- |
| 128 | 0.169 ms | 0.615 ms (3.6x) | 0.596 ms (3.5x) |
| 512 | 0.405 ms | 0.850 ms (2.1x) | 1.118 ms (2.8x) |
| 1024 | 2.271 ms | 2.766 ms (1.2x) | 3.697 ms (1.6x) |

Read the middle column: at n=128 five separate submissions cost as much as five full
round trips WITH their host copies. On unified memory the copies are nearly free and the
submissions are the whole cost. A phase-3 design for Apple should therefore batch
submissions even when it cannot keep data resident.

**Where the crossover lands.** `--simd` on the JVM on the same machine, warm, 20 reps,
against the Metal path including both copies:

| n | `--simd` f64 | `--simd` f32 | metal f32 w/copy | metal f32 kernel |
| --- | --- | --- | --- | --- |
| 64 | 0.150 | **0.050** | 0.191 | 0.179 |
| 128 | 0.550 | 0.300 | **0.217** | 0.201 |
| 256 | 2.600 | 1.450 | **0.270** | 0.260 |
| 512 | 22.100 | 11.350 | **0.836** | 0.792 |
| 1024 | -- | -- | 1.378 | 1.180 |
| 2048 | -- | -- | 8.832 | 8.070 |

The `--simd` columns are POST-todo-469, re-measured on this machine after `5a3e8f16` gave
the f32 kernel its lanes; the GPU columns are unchanged, since nothing about them depends
on rontolisp. That landing matters here because f32 is the only width Metal can serve, so
it moved the exact column `--gpu` has to beat -- `#f` matmul went from 2x slower than `#d`
to 2x faster, which cut the GPU's margin at n=128 from 3.2x to 1.4x. The crossover is
still between n=64 and n=128, but it is now a genuinely marginal win there rather than a
comfortable one, and the honest reading is that Metal only clearly pays from n=256.

Same shape as CUDA's table otherwise, shifted right by the higher floor. The batched
rank-3 product behaves the same way it does on CUDA -- 0.355 ms at 48 x (256 x 64), 3.684
ms at 192 x (512 x 64), 1749 GFLOP/s -- so phase 4's ordering does not change.

**Precision is a BETTER story here, with one exception.** The CUDA f64 tiled kernel differs
from the scalar oracle by max abs 1.5-2.7 because the tile walk reorders the reduction. At
f32 on Metal the GPU lands within 8.5e-7 relative of the f64 oracle at n=512 -- and a CPU
f32 accumulation of the same product lands at 8.7e-7, i.e. **the divergence is "f32 is
f32", not "the GPU reordered something"**. Measured on random zero-mean inputs, because
dyadic test data round-trips exactly and hides the whole question (the same artifact
`MatmulFProbe` hit). The exception is the ufunc tier: MSL's `tanh` differs from
`Math.tanh` in 2002 of 4096 cells, up to 4.87e-5 relative. `MTLCompileOptions` defaults to
`mathMode` 2 (relaxed) / `fastMathEnabled` YES, but setting it to safe or fast changes
neither result -- so this is simply what the device's transcendental is, and phase 4 must
state a tolerance rather than hope for identity.

### The two OS libraries, and what they do to the plan on Apple

The cuBLAS question has a different answer here, and then a second library shows up that
has no CUDA counterpart at all. Both ship inside macOS: no toolkit, no download, no
dependency, no size cost.

**MPS (`MPSMatrixMultiplication`) is worth taking, unlike cuBLAS.** f32, ms per call:

| n | ours resident | MPS resident | ours + copy | MPS + copy |
| --- | --- | --- | --- | --- |
| 128 | 0.174 | 0.191 | 0.184 | 0.186 |
| 256 | 0.242 | 0.200 | 0.247 | 0.214 |
| 512 | 0.752 | 0.296 | 0.305 | 0.214 |
| 1024 | 1.119 | 0.321 | 1.327 | 0.548 |
| 2048 | 9.949 | 1.825 | 10.718 | 2.649 |

Every argument that killed cuBLAS is absent: there is no 660 MB toolkit (it is in the OS),
there is no f64 regression to weigh (there is no f64), and -- the surprise --
**MPS and the naive tiled kernel are bit-identical**, 0 differing cells out of 1,048,576
at n=1024, both 1.28e-6 from the f64 oracle, verified against a poisoned output buffer so
it is agreement rather than a silent no-op. So adopting MPS costs nothing in the precision
contract and buys 5.5x at n=2048. It is still `objc_msgSend` over FFM, four more
signatures.

**Accelerate's CPU BLAS beats both of them at the sizes rontolisp actually runs, and it
has a double.** `cblas_dgemm` / `cblas_sgemm` out of `Accelerate.framework`: plain C, four
lines of FFM, in the OS since forever.

| n | dgemm f64 | sgemm f32 | vs `--simd` f64 | vs metal f32 + copy |
| --- | --- | --- | --- | --- |
| 128 | 0.012 ms | 0.005 ms | 50x | 18x |
| 256 | 0.074 ms | 0.025 ms | 39x | 4x |
| 512 | 0.341 ms | 0.094 ms | 74x | 2.4x |
| 1024 | 2.645 ms | 0.743 ms | -- | 1.9x |
| 2048 | 21.504 ms | 5.296 ms | -- | 1.7x |

800 GFLOP/s at f64 -- nearly TWICE the GB10's cuBLAS DGEMM (420) -- and 3200 GFLOP/s at
f32. It beats the hand-written Metal kernel at every size measured, beats MPS below
n~1024, has no 85 us floor (n=64 costs 4 us), and is the only one of the three that can
touch `linalg`'s default width at all.

**So the Apple conclusion is not the CUDA conclusion.** On the GB10 the built-in PTX is the
answer and a tuned library is a bad trade. On Apple Silicon a hand-written MSL kernel is
the WORST of the three options at every size a rontolisp program plausibly runs, and it is
the one that costs the most to write and maintain. If `--gpu` ships on Apple it should
dispatch through MPS, not through our own `gemm_f32`; and the flag it most wants next to
it is not a GPU flag at all. That is a separate feature over the same interception seam,
and it is recorded as its own item rather than smuggled in here.

## The two contracts this breaks, and what to write down instead

1. **Bit-identity across backends.** This got easier while the spike was being written.
   todo-469 (`5a3e8f16`) moved the `#f` matrix product OFF bit-identity and into the
   single-precision reduction contract that `sum`/`dot`/GEMV already followed, so at f32
   the seat `--gpu` needs is already occupied and `.kb/linalg-simd.md` already states the
   terms. What is left to write down is narrower than it was:
   - At **f32** the Metal product lands 8.5e-7 from the f64 oracle, while a CPU f32
     accumulation of the same product lands 8.7e-7 -- the divergence is the WIDTH, not the
     GPU, and it is the same order as the kernel `--simd` now ships. MPS is bit-identical
     to our own tiled kernel, so which kernel is dispatched is not a contract question
     either.
   - At **f64** it is still a real break: the CUDA tiled kernel reorders its reduction
     (max abs diff 1.5-2.7 at n<=2048), where `#d` under `--simd` remains bit-identical to
     the oracle. This is the CUDA-only case, since Metal has no f64 to diverge in.
   - The **ufunc tier** is the genuine new exception: the device's own transcendental
     differs from `Math.tanh` in half the cells (up to 4.87e-5 relative), unaffected by
     `MTLCompileOptions.mathMode`. Precedent exists (todo-106's opt-in precision contract in
   `.kb/linalg-simd.md`): state it as opt-in, keep the scalar defun as the cross-backend
   oracle, keep `--gpu` out of `ci-spec.yaml`, and pin the GPU path against the scalar
   path at a RELATIVE tolerance, the way `TorchGradcheck` already compares analytic
   against numerical gradients at 1e-3.
2. **Element mutation invalidates a cached device buffer.** Any residency scheme
   (phase 3) needs a rule for what happens when `(setf (aref a i j))` writes into an
   array whose device copy is live. `linalg` results are fresh copies and nothing
   aliases (`.kb/torch.md`), which is what makes an identity-keyed device cache sound at
   all -- but `%la-make` + `setf aref` is how every result is BUILT, and
   `torch:set-data` replaces a tensor's data in place. Do not design the cache before
   enumerating every in-place write on a packed array.

## Phase 0: `torch:` defaults to single-float (do this BEFORE any GPU work)

Decided 2026-08-20 after the 44x measurement above. It is listed as phase 0 rather than
as a nice-to-have because **without it every `--gpu` measurement is contaminated**: an
f32 speedup measured against an f64 CPU baseline confuses "the GPU is fast" with "the
default width was wrong", and the flag would ship tuned against the wrong reference.

### Scope, which is narrower than it sounds

This is NOT about scalar floats or the reader. rontolisp has exactly ONE float type and
it is f64 -- `(type-of 1.0)` answers `FLOAT`, `LispNames` records that "every float
shares the one double", and `*read-default-float-format*` has nothing to bind. Only the
PACKED ARRAY element type is in question, i.e. `linalg::%la-make`'s default and who
threads an `:element-type` into it.

### The split: torch flips, linalg does not

- **`torch:` -> single-float.** PyTorch's own default dtype IS `torch.float32`, so the
  current `#d` default is a deviation from the library `.kb/torch.md` says this package
  mirrors. Its workloads (`examples/llm-from-scratch/`,
  `examples/deep-learning-from-scratch/`, `examples/ml/tiny-llm.lisp`) are exactly the
  ones the device is for, its numerics are trained-network numerics where f32 is the
  industry norm, and it is where the 44x lands.
- **`linalg:` -> stays double-float.** numpy's default is float64 too; `inv`/`solve`/
  `det` and the numerical-calculus / `heat3d` examples lose real accuracy at f32; and
  linalg is the cross-backend byte-identity oracle. Nothing needs to change here: the
  width polymorphism of todo-097 already PRESERVES an input's width through every
  transform, so an `#f` tensor entering linalg comes out `#f` all the way through the
  forward and backward pass. **That existing mechanism is what makes phase 0 a small
  change instead of an architectural one.**

### It is not enough to change `torch:tensor`

The trap: `torch:linear` builds its weights with `linalg:uniform` and passes no
`:element-type`, so they would stay `#d` while activations went `#f`. Mixed widths are a
DECLINE condition for every `--simd` kernel, so the program would fall back to the scalar
defun everywhere -- far worse than either width consistently. Every site that ORIGINATES
a width must move together. There are only six: `torch:linear` (weight + bias),
`torch:embedding` (table), `torch:layer-norm` (gain + bias) and `torch:tensor`'s own
`%t-as-data`; everything else in torch.lisp goes through `zeros-like` / `from-list` /
`full` and inherits its width already.

Give them a package default rather than six literals -- `torch::*default-element-type*`,
a `defparameter` read at each origination site, following `torch::*grad-enabled*`'s
precedent (`.kb/torch.md`, and `torch:no-grad` shows the dynamic-rebinding pattern if a
`with-dtype` form is ever wanted). This is legal despite `%la-make`'s "literal
`:element-type`" rule: that rule is about the two `make-array` calls INSIDE `%la-make`,
each of which stays literal so every backend still picks `double[]` / `float[]`
statically. `%la-make`'s own parameter is already a runtime value.

### Prerequisite and risks

- **The `#f` matmul prerequisite is DONE (2026-08-20), so phase 0 is unblocked.** The
  `--simd` matrix product runs f32 lanes with an f32 accumulator on all three backends
  now, and `#f` matmul is FASTER than `#d` rather than 2.0x slower (x86-64, n=512:
  interpreter 25.8 vs 51.6 ms, JVM 28.9 vs 53.5). It also settled the precision decision
  phase 0 depended on: an `#f` matrix product follows the single-precision reduction
  contract instead of being bit-identical to the scalar defun, and is deterministic
  across all three `--simd` backends (`.kb/linalg-simd.md`).
- **Pin `TorchGradcheck` to double-float explicitly.** It central-differences at
  `eps 1e-4` against `tol 1e-3` relative; at f32 the subtraction leaves roughly three
  significant digits, which is at or past the tolerance. The gradcheck exists to verify
  ADJOINTS, not the default dtype, so it should say `:element-type 'double-float` and
  keep its current sensitivity rather than have its tolerance loosened.
- **Churn, not danger.** Every `.expected` file and every doc example's `; =>` changes,
  mirrored across `doc/en/**` and `doc/ja/**` with byte-identical fences. `#f` itself is
  already proven cross-backend -- the `linalg-single-float-cross-backend` ci-spec case
  pins it -- so this is volume, not risk. `DocExamplesTest#fixShownResults` does most of
  it mechanically.
- **Mixed-width regressions in user code.** `(torch:add tensor (linalg:ones ...))` now
  mixes `#f` and `#d` and declines to the scalar defun. Correct, just slow. Worth a
  sentence in `doc/{en,ja}/guides/neural-networks.md` telling people to build linalg
  operands for a tensor with `:element-type 'single-float`.

### Acceptance

- A `torch:` program built from `torch:tensor` + `torch:linear` + `torch:embedding` +
  `torch:layer-norm` is `#f` end to end -- assert the element type after a full
  forward/backward, so a single missed origination site fails loudly instead of silently
  declining every kernel.
- `train-gpt-soseki.lisp` and `tiny-llm.lisp` get FASTER on all three `--simd` backends,
  not just non-slower.
- The gradcheck table still passes at its current `tol`, running in double.
- Byte-identical output with and without `--simd`, on all four backends, as today -- the
  examples round their printed floats, which is what absorbs the `#f` reduction contract
  (the matrix product included, since it joined that contract).

## Phases

Each phase is separately shippable and separately measurable. Do not start a phase
without the previous one's numbers. Phase 0 above comes first.

**Status.** Phase 0 LANDED 2026-08-20 (`a9a5b2e4`, behind todo-467's `3511d10f`).
Phase 1 LANDED 2026-08-21: `am.ik.gpu` in `51b872b3` + `d0b54738`, the interceptor and
the flag in `02dfd287`. Phase 2 LANDED the same day (`ce1787f8`), and settled the
bridge question below the other way: the emitted `.class` carries `am.ik.gpu`'s own
class files, because the blob costs no more than the `--simd` template already does and
a flattened copy would fork exactly the parts phase 1 was spent on. The design, the
measurements and the open items now live in `.kb/gpu.md`; where that file and this one
disagree, `.kb/gpu.md` was measured second and wins -- in particular the precision break
is FUSED multiply-add, not a reordered reduction, and the native image's per-call cost
is an INTERPRETER problem that compiling to a class walks around.

**Phase 4's first item runs BEFORE phase 3, deliberately.** Phase 3's own measurement
above is a five-op chain of which only the two products are intercepted members, so
residency has almost nothing to hold until more of the chain is on the device -- while
`%la-matmul-nd` is compute-bound and pays with no residency at all. It is also the shape
that matters: `--gpu` declines every rank >= 3 product today, so a transformer gains
nothing from the flag, which is the workload this file exists for. Doing it first also
gives phase 3 a real chain to measure. Order is therefore 0, 1, 2, 4a
(`%la-matmul-nd`), 3, 4b (the element-wise tier and the ufuncs).

1. **`--gpu` on the interpreter, one member: `linalg:dot`'s M.M case.** `am.ik.gpu` +
   `eval/LinalgGpu` + `eval/LinalgGpuKernels` + the checked-in PTX + the availability
   probe (no device, no driver, old card, or `libcuda.so.1` absent -> the flag is a
   silent no-op, exactly like `--simd` without `jdk.incubator.vector`). Copies per
   call, size threshold from the ~16 us floor. Acceptance: `train-gpt-soseki.lisp` and
   `examples/ml/tiny-llm.lisp` print byte-identical output with and without the flag at
   the CURRENT shapes (they are small enough to decline), and the matmul microbenchmark
   above reproduces.
2. **The JVM backend**, via `JvmLinalgGpuCompiler` + the embedded template decision
   above. Same member, same acceptance, plus the argument-evaluated-exactly-once pin
   that both existing `--simd` compilers carry.
3. **Device residency.** The 2-4x. This is where the design work is: an
   identity-keyed device cache with an explicit invalidation rule (contract 2 above), or
   a device handle inside the packed array. Measure the 5-op chain again through the
   real `torch:` stack, not a synthetic one.
4. **The member set.** `%la-matmul-nd` FIRST -- it is the transformer's whole hot path,
   `--simd` does not intercept it (todo-467), and a batch axis is free on a GPU. Then
   the element-wise tier and the ufuncs, which are memory-bound and therefore only pay
   under phase 3.
5. **Metal.** The `objc_msgSend` route is validated, so this phase is no longer a
   feasibility question -- it is a port of phases 1-3 with three deliberate differences:
   dispatch through **MPS**, not through our own kernel (the naive MSL kernel loses to
   every alternative and is not worth maintaining); treat an `#d` operand as a hard
   decline, since MSL has no double; and make phase 3 batch SUBMISSIONS as well as keep
   data resident, because on Apple the ~85 us floor is per command buffer. Do not port
   the checked-in-kernel-text machinery: MSL compiles at run time from a string, with no
   toolchain and no build-time artifact.

Out of scope, permanently or for now: wasm-GC and the browser (no FFM; a WebGPU path
would be host imports through `rontolisp:wasm-import` with the page's JS owning the
dispatch, which is a different feature), `--no-gc` (no arrays, no linalg), `wasi-nn`
(inference only, so it does not serve training).

## The framing to keep

At the shapes `examples/llm-from-scratch/` runs today -- `*n-embd*` 8, `*block-size*` 8,
batch 4 -- the honest expectation is that `--gpu` declines nearly everything, and
should. The draft said it and it is still true: **this todo is "make a scale we cannot
train today trainable", not "make today's examples faster."** The examples are sized to
finish inside CI on four backends; the flag exists so that raising `*n-embd*` to 384
and `*block-size*` to 256 -- the notebook's own numbers, which `train-gpt-soseki.lisp`
already says are a one-line change -- stops being out of reach.

## References

- `.kb/linalg-simd.md` (the interception protocol, the decline sentinel, the precision
  contract, the three per-backend touch points), `.kb/linalg.md`, `.kb/torch.md`,
  `.kb/vec.md`, `.kb/template-class-embedding.md`.
- `.todo/467-batched-matmul-is-outside-the-simd-intercepted-set.md` -- the same member
  this todo's phase 4 wants, on the CPU. Land 467 first: it is cheaper, it makes the
  scalar side of the comparison honest, and its kernel shape is the one the GPU batch
  kernel mirrors. `.todo/121` and `.todo/468` are the other open member-tier items.
- `../silicon/` -- `silicon-cuda` (pure-FFM driver-API binding, the prototype-string
  `Bindings.java`), `silicon-metal` (the Swift-shim approach we are NOT taking, and did
  not need to: `Mtl.java` reaches the same API through `objc_msgSend`).
- `.kb/linalg-blas.md` -- the tuned-BLAS finding above, LANDED 2026-08-20 as the `--blas`
  flag (todo-470): a CPU item, covering `linalg`'s DEFAULT width,
  recommended-never-required. On Apple Silicon it beats everything in this file below
  n~1024, and on the GB10 with OpenBLAS installed it draws level with `--gpu` at f64
  outright. Two findings there change this file's plan: `Linker.Option.critical` takes
  HEAP segments, so the host-side copy phase 3 wants to remove is already gone on the CPU
  path; and the marker-symbol rule for refusing an untuned library is the same shape a
  `dlopen` of cuBLAS would need.
- `examples/llm-from-scratch/`, `examples/ml/tiny-llm.lisp`,
  `examples/deep-learning-from-scratch/` -- the workloads this is for.
