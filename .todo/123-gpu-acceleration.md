# `--gpu`: a second orthogonal acceleration flag, over the same call sites as `--simd`

**Status:** spiked 2026-08-20 on an NVIDIA GB10 and it works. This file replaces the
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
   23x (f64) / 124x (f32) at n=512. Residency then buys a further 2.0-3.7x. That
   reorders the plan: phase 1 is the `--simd` protocol again, copies and all, and
   residency is phase 3.
4. **The honest limits.** `--gpu` reaches **two of the four backends** (interpreter,
   incl. the native binary, and JVM). WASM has no FFM and is out, so unlike `--simd`
   this flag cannot claim near-parity. And the win is an **f32** win: on this device
   fp64 runs at 1/44 of fp32 throughput, while `linalg`'s default width is
   double-float.

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
| 32 | 0.150 | 0.050 | ~0.017 | -- | -- | -- |
| 64 | 0.450 | 0.400 | **0.043** | 0.025 | 0.036 | 0.020 |
| 128 | 0.500 | 0.750 | **0.072** | 0.029 | 0.037 | 0.016 |
| 256 | 2.800 | 5.200 | **0.198** | 0.088 | 0.088 | 0.026 |
| 512 | 21.200 | 39.800 | **0.906** | 0.576 | 0.320 | 0.120 |
| 1024 | -- | -- | 5.720 | 4.432 | 1.625 | 0.859 |
| 2048 | -- | -- | 40.541 | 35.238 | 9.360 | 6.740 |

For scale, the plain Java triple loop (the shape of the scalar `%la-matmul` defun,
JIT-warm only at the small end): n=512 142 ms, n=1024 1246 ms, n=2048 16.3 s.

Two things to read out of that table. The f32 column is where the GPU actually is --
`--simd` is SLOWER at f32 than f64 (39.8 vs 21.2 ms at n=512) while the GPU is 2.8x
FASTER, so the width that costs the CPU path is the one the device wants. And the
crossover is far lower than the draft guessed, because what `--gpu` has to beat at
small n is not raw CPU FLOPs but rontolisp's own per-call overhead.

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
- **The price is 660 MB**: `libcublas.so.13` is 59 MB but links `libcublasLt.so.13`,
  which is 601 MB. That is the whole CUDA-toolkit-on-the-user's-machine requirement,
  reintroduced, in exchange for a factor that is negative at the default width.

So: the built-in PTX is not a stopgap, it is the answer. Closing the f32 gap is a
self-contained kernel-tuning exercise (register tiling, vectorized loads, bigger tiles)
that needs no dependency and recovers most of it. In ROI order the real wins are
**single-float adoption (44x) > residency (2-4x) > kernel tuning (up to 7x, f32 only)**,
and cuBLAS is a strictly worse way to buy the last of those. Revisit only if phase 3
lands, f32 workloads dominate in practice, and someone still wants the last 2-3x on a
machine that already has the toolkit -- as an opportunistic `dlopen`, never a
requirement.

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

### Metal: designed, NOT spiked

No Apple Silicon was available, so everything here is a sketch with its risks named,
and phase 5 starts by validating it -- do not treat it as settled.

- **Runtime compilation is built into the OS.** `newLibraryWithSource:options:error:`
  compiles MSL at runtime, so Metal needs no shipped binary blob and no toolchain --
  the equivalent of the PTX story, and arguably simpler.
- **Everything else is Objective-C.** `MTLCreateSystemDefaultDevice()` is a plain C
  entry point in `Metal.framework`, but from there it is `objc_msgSend` on
  `libobjc.A.dylib`, one FFM downcall handle per distinct signature. Feasible; more
  fiddly than CUDA by a wide margin.
- **The reference library in `../silicon/` does NOT do this** -- `silicon-metal` calls
  into a Swift shim (`silicon-metal/native/src/*.swift`) built per platform. That is
  exactly the bundled-native-artifact we cannot have, which is why `--gpu` must go the
  `objc_msgSend` route rather than port silicon's Metal backend.
- Read `../silicon/` for the CUDA side too: `silicon-cuda` is pure FFM against
  `libcuda.so.1` and its `Bindings.java` (a C-prototype-string -> `FunctionDescriptor`
  parser) is a genuinely nice idea worth stealing. Its NVRTC-at-runtime and Slang
  cross-compilation choices are the ones we are deliberately not taking.

## The two contracts this breaks, and what to write down instead

1. **Bit-identity across backends.** A GPU product reorders its reductions
   (measured: max abs diff 1.5-2.7 at n<=2048 f64). The matrix product is today EXEMPT
   from the f32-reduction contract -- it is bit-identical under `--simd` -- and `--gpu`
   cannot keep that. Precedent exists (todo-106's opt-in precision contract in
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

## Phases

Each phase is separately shippable and separately measurable. Do not start a phase
without the previous one's numbers.

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
5. **Metal**, starting by validating the `objc_msgSend` sketch on real hardware.

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
  `Bindings.java`), `silicon-metal` (the Swift-shim approach we are NOT taking).
- `examples/llm-from-scratch/`, `examples/ml/tiny-llm.lisp`,
  `examples/deep-learning-from-scratch/` -- the workloads this is for.
