# The `--gpu` feasibility spike (CUDA 2026-08-20, Metal 2026-08-20)

Throwaway probes kept for reproducibility, NOT project code: they are outside `src/`, are
not in the reactor, are not formatted by `spring-javaformat:apply`, and nothing builds or
tests them. They exist so that the numbers in `../123-gpu-acceleration.md` can be
re-derived on other hardware -- especially the decisions those numbers drove (per-call
intercept before residency; PTX-in-the-jar instead of a runtime toolkit; and, on Apple,
that the naive kernel is the wrong thing to ship at all).

Every file is a single-class JDK source-launcher program: no build step, no dependency,
Java 22+ for the FFM API. `--enable-native-access=ALL-UNNAMED` silences the restricted-
method warning. `Cu*`/`*Spike`/`Ptx*`/`Ni*`/`Tf32*` are the CUDA side and run only on a
machine with an NVIDIA driver; `Mtl*` and `AccelerateProbe` are the Apple side and run
only on macOS. Nothing here runs on both.

## The two machines these numbers came from

- **CUDA:** NVIDIA GB10 (Grace Blackwell, `sm_121`, 48 SMs, unified addressing + managed
  memory), aarch64, driver 580.173.02 / CUDA 13.0, Oracle GraalVM 25.0.4. `nvidia-smi`
  and `/usr/local/cuda` present.
- **Metal:** Apple M4 Max (40 GPU cores, Metal 4, unified memory, 110 GB recommended
  working set), macOS 26.3.1, Oracle GraalVM 25.0.3. **No Xcode**: `xcrun metal` is
  absent, which is itself a result -- see `MtlCompileCost`.

A different device changes every number below; what should survive is the SHAPE of each
result. One Apple-specific caveat before reading any of it: Apple GPUs ramp their clocks,
so a short warm-up under-reports, and a kernel that runs for well under a millisecond can
flap by 2-3x between runs. Every `Mtl*` probe reports a min over many reps after a
warm-up, and the sub-millisecond rows still move by ~20% run to run.

## The files

| file | question it answers |
| --- | --- |
| `Cu.java` | the binding itself: `libcuda.so.1` + `libnvrtc.so.13` through pure FFM. Every other file uses it. Nothing else here is more than a driver for it. |
| `MatmulSpike.java` | does a GPU matmul beat the CPU, and where is the crossover? Also holds `SRC`, the CUDA C the other probes compile. |
| `DumpPtx.java` | writes `gemm_<arch>.ptx` -- the build-time artifact the real feature would check in as a resource. |
| `PtxSpike.java` | the three questions the design hangs on: (1) does checked-in PTX load with ONLY the driver, (2) does unified memory remove the copy, (3) how far is the naive kernel from cuBLAS. |
| `ResidencySpike.java` | the 2026-07-13 draft's crux: must arrays LIVE on the device, or does a per-call intercept pay? Plus the batched rank-3 product (todo-467's member). |
| `TinySpike.java` | the fixed per-call floor, which is what the size threshold is built on. |
| `Tf32Check.java` | rules out the obvious objection to the 44x f32/f64 gap: is cuBLAS's f32 row secretly TF32? |
| `CublasEndToEnd.java` | and is cuBLAS worth the toolkit at all? Both kernels, both phases (with copies / resident), both widths. |
| `NiProbe.java` | does a CUDA downcall survive GraalVM native-image next to `-H:+VectorAPISupport`? |
| `MatmulFProbe.java` | no GPU at all: why was `#f` matmul SLOWER than `#d` under `--simd`, and what would fix it? Drove todo-469 (landed `5a3e8f16`, 2026-08-20 -- the kernel now takes f32 lanes); kept because `.kb/linalg-simd.md` cites it and it answers differently per architecture. |
| `matmul-baseline.lisp` | the CPU side of the comparison -- `linalg:matmul` under `--simd`, warm, 20 reps. Not a GPU program. |
| `CuLib.java` | the DRIVER-ONLY binding the three `am.ik.gpu` probes below share: no NVRTC, no toolkit, the checked-in `src/main/resources/am/ik/gpu/gemm.ptx` loaded straight from the repository. `Cu.java` one design generation on -- deliberately the shape the shipped library has, so its numbers are the library's numbers. |
| `AllocatorCost.java` | what does PER-CALL device memory cost, and what does that do to the floor? The question the spike probes above never asked, because every one of them allocated once and then looped. Also: what a FAILED pooled allocation costs the device, and the two calls needed to give it back. |
| `CopyRoute.java` | does `Linker.Option.critical` remove the host copy here as it did for `--blas`, and what bounds the window in which the thread cannot reach a safepoint? Includes the GPU-only half of that question -- a device-to-host copy on the null stream also waits for the KERNEL. |
| `WorthCrossover.java` | the GPU half of `am.ik.gpu`'s `worth()` threshold, in the shipped route. The CPU half is `matmul-baseline-warm.lisp`. |
| `matmul-baseline-warm.lisp` | the same CPU baseline as `matmul-baseline.lisp`, JIT-warm (200 warm-ups, 4000 reps) and down to n=16. The original's 3 warm-ups over-report n<=64 by ~10x, which is exactly the range the size threshold is decided in. Not a GPU program. |
| `width-baseline.lisp` | `#f` against `#d` across matmul / add / dot / exp / sum, which is how the matmul anomaly surfaced. Not a GPU program. |
| `ElementwiseCrossover.java` | phase 4b's question: does an ELEMENT-WISE member pay for a round trip, and WHICH ones? Round trip against `--simd` at seven sizes and both widths, for the members the library takes AND the ones it refuses -- a decline is a measurement too. Its third table is phase 3's input: the same kernel over buffers already on the device, which is what residency would remove. |
| `elementwise-probe.cu` | the kernels that probe uses -- NOT the shipped ones. It carries `sqrt` / `abs` / `negative` and a binary `add`/`sub`/`mul`/`div` zip, which `am.ik.gpu` deliberately does not, so the refusals stay re-derivable. `nvcc -arch=compute_75 -ptx elementwise-probe.cu -o /tmp/ew.ptx`, then pass that path to the probe. |
| `elementwise-baseline.lisp` | the CPU side of that crossover: the same members under `--simd` on the JVM class output, JIT-warm, at the same sizes. Every call is a LITERAL call form -- `(funcall #'linalg:exp a)` would measure the scalar defun, since the compiled backends intercept at the call site. Not a GPU program. |
| `StridedCrossover.java` | phase 3's question: the members a training step still spends its time on are a BROADCAST binary op, an AXIS fold and an axes TRANSPOSE, and their CPU twins are SCALAR ODOMETER walks rather than the lane loops phase 4b measured `add` and `sqrt` at. Measures all three through the shipped route, at the shapes `train-gpt-soseki.lisp` produces at the notebook's own and at a size sweep for the two thresholds. Its CPU half is `shaped-baseline.lisp`. Needs the checked-in PTX to carry the six strided entry points, so run it against a tree at or after phase 3. |
| `shaped-baseline.lisp` | the CPU side of that crossover: the same members under `--simd` on the JVM class output, at the same shapes and the same sweep, plus the whole `softmax` / `log-softmax` / `layer-norm` chains for the end-to-end comparison. Run it twice, once per flag, and pair the columns. Not a GPU program. |
| `elementwise-precision.lisp` | phase 4b's OTHER question: how far does a device transcendental sit from the scalar defun, per member and per width? It prints values rather than differences, because the two sides are two RUNS -- run it with `--simd` and with `--simd --gpu` and diff the files with the script in its header. The precision table in `.kb/gpu.md` is that script's output on the JVM class output. Runs anywhere: with no device the two files are identical, which is itself the check that the flag is the only variable. |

### The Metal files (macOS)

| file | question it answers |
| --- | --- |
| `Mtl.java` | the binding itself: Metal through `objc_msgSend` over pure FFM, no Swift shim and no bundled dylib. Every `Mtl*` file uses it. The CUDA side's `Cu.java`, one platform over. |
| `MtlF64Probe.java` | the decisive one, and it is not about speed: does MSL have a `double`? Also `half` / `bfloat` / 64-bit int. |
| `MtlSpike.java` | does a Metal matmul beat the CPU, and where is the crossover? Holds `SRC`, the MSL the other probes compile. |
| `MtlTiny.java` | the fixed per-call floor -- and, because Metal's floor is 5x CUDA's, whether spinning on the command buffer's status beats `waitUntilCompleted`, and how much batching several dispatches into ONE command buffer amortizes. |
| `MtlResidency.java` | must arrays LIVE on the device? Measures three ways, not two, because Metal has two separate costs to remove. Plus the batched rank-3 product. |
| `MtlPrecision.java` | the precision contract: how far f32 lands from the f64 scalar oracle on inputs that do NOT round-trip exactly, and whether MSL's default compile options are doing fast-math behind our back. |
| `MtlMps.java` | the cuBLAS question, re-asked where the tuned library is IN THE OS: is `MPSMatrixMultiplication` worth using? |
| `MtlMpsDiff.java` | verifies the surprising half of that answer -- MPS and the naive tiled kernel are bit-identical, which a silent no-op would also look like. |
| `MtlCompileCost.java` | the PTX question restated: what does getting a kernel onto the device cost at startup, and does the OS cache it between processes? |
| `MtlNiProbe.java` | does an `objc_msgSend` downcall survive GraalVM native-image next to `-H:+VectorAPISupport`? |
| `AccelerateProbe.java` | no GPU at all: a tuned BLAS is plain C, costs no dependency, and unlike Metal it has a double. How fast is it, is one PRESENT, and is the one that is present actually TUNED? Walks a candidate list (Accelerate, NVPL, OpenBLAS, MKL, distro `libblas`), identifies what it bound and prints a verdict against measured throughput. Runs on either machine -- the probe that reframes the Apple plan, and the one that stopped it being reframed the same way on Linux. |

## Running them

```bash
cd .todo/123-gpu-acceleration

# the CPU baseline to beat (rontolisp's fastest path today)
JAR=../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -jar $JAR matmul-baseline.lisp -o Mm2.class --simd   # keep the -o name path-free
java --add-modules jdk.incubator.vector Mm2

# the GPU probes
java --enable-native-access=ALL-UNNAMED MatmulSpike.java
java --enable-native-access=ALL-UNNAMED TinySpike.java
java --enable-native-access=ALL-UNNAMED ResidencySpike.java
java --enable-native-access=ALL-UNNAMED Tf32Check.java      # needs libcublas (toolkit)
java --enable-native-access=ALL-UNNAMED CublasEndToEnd.java # needs libcublas (toolkit)

# the width probes -- no GPU involved, Vector API only
java --add-modules jdk.incubator.vector MatmulFProbe.java
java -jar $JAR width-baseline.lisp -o W.class --simd && java --add-modules jdk.incubator.vector W

# PtxSpike needs the PTX first; 75 is CUDA 13's oldest accepted virtual arch
java --enable-native-access=ALL-UNNAMED DumpPtx.java 75
java --enable-native-access=ALL-UNNAMED PtxSpike.java
```

The three `am.ik.gpu` probes need the driver and nothing else -- no toolkit, because they
load the kernels the library ships rather than compiling any. They are the ones whose
numbers `.kb/gpu.md` quotes, so they are the ones to re-run on new hardware:

```bash
java --enable-native-access=ALL-UNNAMED AllocatorCost.java
java --enable-native-access=ALL-UNNAMED CopyRoute.java
java --enable-native-access=ALL-UNNAMED WorthCrossover.java

# the CPU column WorthCrossover has to be read against, JIT-warm
JAR=../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -jar $JAR matmul-baseline-warm.lisp -o Mm3.class --simd
java --add-modules jdk.incubator.vector Mm3
```

`CuLib.java` is picked up automatically by the source launcher, the same rule as `Cu.java`.

`Cu.java` and `MatmulSpike.java` are picked up automatically by the source launcher --
do not pass them as arguments, or they land in `args` instead (that mistake is why
`DumpPtx` first appeared to reject every `--gpu-architecture`).

### The Metal probes (macOS)

```bash
cd .todo/123-gpu-acceleration

# the CPU baseline to beat, same two programs as the CUDA side
JAR=../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar
java -jar $JAR matmul-baseline.lisp -o Mm2.class --simd   # keep the -o name path-free
java --add-modules jdk.incubator.vector Mm2

java --enable-native-access=ALL-UNNAMED MtlF64Probe.java
java --enable-native-access=ALL-UNNAMED MtlSpike.java
java --enable-native-access=ALL-UNNAMED MtlTiny.java
java --enable-native-access=ALL-UNNAMED MtlResidency.java
java --enable-native-access=ALL-UNNAMED MtlPrecision.java
java --enable-native-access=ALL-UNNAMED MtlMps.java
java --enable-native-access=ALL-UNNAMED MtlMpsDiff.java
java --enable-native-access=ALL-UNNAMED AccelerateProbe.java
java --enable-native-access=ALL-UNNAMED MtlCompileCost.java   # run it three times
```

`Mtl.java` and `MtlSpike.java` are picked up automatically, same rule as above. None of
these needs Xcode, a toolchain, or a build step -- which is the point of
`MtlCompileCost`.

The native-image leg is the same recipe with the Apple classes:

```bash
javac -d classes MtlNiProbe.java Mtl.java MtlSpike.java
java --enable-native-access=ALL-UNNAMED \
     -agentlib:native-image-agent=config-output-dir=ni-config-mtl -cp classes MtlNiProbe
mkdir -p classes/META-INF/native-image/spike-mtl
cp ni-config-mtl/reachability-metadata.json classes/META-INF/native-image/spike-mtl/
native-image --no-fallback --enable-native-access=ALL-UNNAMED \
             --add-modules jdk.incubator.vector \
             -H:+UnlockExperimentalVMOptions -H:+VectorAPISupport \
             -cp classes MtlNiProbe mtlniprobe
./mtlniprobe
```

The agent's `foreign.downcalls` section here carries an entry the CUDA side never needed:
`{"returnType": "void", "parameterTypes": ["void*", "void*", "struct(jlong,jlong,jlong)",
"struct(jlong,jlong,jlong)"]}` -- `dispatchThreadgroups:threadsPerThreadgroup:` taking two
`MTLSize`s by value. It built in 19.3 s and ran:

```
MtlNiProbe OK on Apple M4 Max: n=512 f32 gemm 0.864 ms, C[0]=-0.938
```

Same answer as the JVM, so Metal does not re-enter the `VectorAPISupport` /
`SharedArenaSupport` fight either.

### The native-image leg

`NiProbe` is the one that needs a real build. Foreign downcalls must be registered, and
the tracing agent writes the registration for you:

```bash
javac -d classes NiProbe.java Cu.java MatmulSpike.java
java --enable-native-access=ALL-UNNAMED \
     -agentlib:native-image-agent=config-output-dir=ni-config -cp classes NiProbe
mkdir -p classes/META-INF/native-image/spike
cp ni-config/reachability-metadata.json classes/META-INF/native-image/spike/
native-image --no-fallback --enable-native-access=ALL-UNNAMED \
             --add-modules jdk.incubator.vector \
             -H:+UnlockExperimentalVMOptions -H:+VectorAPISupport \
             -cp classes NiProbe niprobe
./niprobe
```

The `--add-modules` / `-H:+VectorAPISupport` pair is not incidental: it reproduces the
real binary's build flags, which is the whole point -- todo-102 found `VectorAPISupport`
and `SharedArenaSupport` mutually exclusive, and this proves CUDA does not re-enter that
fight. Without the metadata the binary fails at the FIRST downcall with
`MissingForeignRegistrationError`; with it:

```
NiProbe OK on NVIDIA GB10: n=512 f64 gemm 0.585 ms, C[0]=638.000
```

## What the `am.ik.gpu` probes printed (2026-08-20, GB10)

These three are the evidence behind `.kb/gpu.md`, and each of them corrects something the
CUDA spike above got wrong.

```
$ java AllocatorCost.java
NVIDIA GB10, 48 SMs, checked-in compute_75 PTX loaded in 8.87 ms

-- one allocate + free pair, in isolation --
         4096 B   cuMemAllocAsync   2.26 us | cuMemAlloc   138.82 us  (62x)
      1048576 B   cuMemAllocAsync   0.67 us | cuMemAlloc   136.24 us  (203x)
      8388608 B   cuMemAllocAsync   0.66 us | cuMemAlloc   336.24 us  (513x)

-- a whole f64 product, both allocators, three buffers a call --
    n           pooled us    unpooled us      ratio    per pair us
    64               26.4          181.5        6.9           51.7
    128              42.5          209.4        4.9           55.6
    256             125.7          302.7        2.4           59.0
    512             692.2         1203.2        1.7          170.3

-- what a FAILED pooled allocation costs the device --
    free before                              117774 MB
    two 67079 MB requests: second -> CUresult 2
    free with the first still held            49350 MB
    free after cuMemFreeAsync, no trim        49350 MB
    free after cuMemPoolTrimTo, NO sync       49350 MB
    free after cuCtxSynchronize + trim       117787 MB
```

Three separate results. **The allocator is the floor**: `cuMemAlloc` is 62-513x a pooled
allocation in isolation, and three of them per product is the difference between a 26 us
call and a 181 us one -- so the spike's "16-18 us floor" was only ever true of a loop that
allocated once, and a real per-call intercept has to use `cuMemAllocAsync`. Note that the
isolated 137 us pair and the ~52-59 us the same pair costs inside a steady product loop are
DIFFERENT measurements; the floor comes from the product column, not from multiplying the
isolated one by three. **And a failed pooled allocation is not free**: it grows the pool on
the way to failing, `cuMemFreeAsync` is stream-ordered so the buffers are not back yet, and
a trim issued before `cuCtxSynchronize` finds them still in use and returns success having
done nothing. All three lines are needed, in that order, or a declined product silently
holds 68 GB for the life of the process.

```
$ java CopyRoute.java
-- one f64 product end to end, allocation included --
    n             staged us      critical us    ratio
    8                  26.1             21.0     1.24
    32                 23.3             19.6     1.19
    64                 31.3             20.9     1.49
    128                72.1             40.9     1.76
    256               228.2            128.1     1.78
    512              1055.3            693.4     1.52
    1024            15462.5           4856.6     3.18
    2048            75286.9          36878.7     2.04

-- how long one CRITICAL call holds the thread off a safepoint --
    n           MB/op     HtoD crit us    DtoH after launch    DtoH after sync
    128           0.1              7.7                 21.6                7.5
    256           0.5             14.5                 93.2               13.4
    512           2.1             41.4                608.0               40.7
    1024          8.4            141.8               4564.9              141.7
    2048         33.6            569.9              35721.6              547.8
    4096        134.2           2261.3             283241.9             2243.1
```

The first table kills todo-123's "the heap copy is unavoidable in every row": a critical
downcall takes the heap array directly, and the gap WIDENS with size rather than closing,
because the staging buffer is a native allocation of the operand's size on every call. The
second table is the GPU-only hazard: compare the last two columns. A device-to-host copy
issued straight after a launch is not a copy, it is a wait -- 283 ms of it at n=4096 --
and putting `cuCtxSynchronize` in front of it costs 0.26 us and gives back a window bounded
by bytes.

```
$ java WorthCrossover.java
n           f64 us/call    f32 us/call     n*m*p
16                 18.3           17.6     4096
32                 18.2           16.1     32768
40                 19.0           15.8     64000
48                 19.2           16.5     110592
56                 20.3           18.2     175616
64                 21.3           16.7     262144
96                 36.8           19.3     884736
128                42.8           21.9     2097152
256               123.9           51.0     16777216
512               692.1          183.3     134217728

$ java --add-modules jdk.incubator.vector Mm3   # matmul-baseline-warm.lisp
n=32 f64 9.50 us   n=32 f32 6.25 us
n=40 f64 11.75 us  n=40 f32 7.50 us
n=48 f64 23.00 us  n=48 f32 14.00 us
n=56 f64 34.50 us  n=56 f32 20.25 us
n=64 f64 49.00 us  n=64 f32 28.50 us
n=96 f64 131.00 us n=96 f32 73.50 us
n=128 f64 383.75 us n=128 f32 191.00 us
```

Read the two together: the crossover is n~45 at f64 and n~51 at f32, so `am.ik.gpu` sets
`worth()` at `n*m*p >= 2^17`. The `--simd` column in `../123-gpu-acceleration.md` is the
same program with 3 warm-ups and 20 reps, which reports 100 us at n=32 where a warm run
costs 9.5 -- about 10x, and all of it at exactly the sizes the threshold is decided by.

## What they printed

Recorded verbatim so a later run can be diffed against them. The interpretation of each
table is in `../123-gpu-acceleration.md`; this is only the raw evidence.

```
$ java MatmulSpike.java
device: NVIDIA GB10  sm_121  SMs=48 unified-addr=1 managed=1
nvrtc compile+load: 145.6 ms
n=64    cpu(java f64)     0.65 ms | gpu f64   0.043 ms w/copy,   0.025 ms kernel | gpu f32   0.036 /   0.020 | f64 exact-vs-cpu maxdiff 2.28125
n=128   cpu(java f64)     1.52 ms | gpu f64   0.072 ms w/copy,   0.029 ms kernel | gpu f32   0.037 /   0.016 | f64 exact-vs-cpu maxdiff 1.90625
n=256   cpu(java f64)    11.07 ms | gpu f64   0.198 ms w/copy,   0.088 ms kernel | gpu f32   0.088 /   0.026 | f64 exact-vs-cpu maxdiff 1.8125
n=512   cpu(java f64)   142.25 ms | gpu f64   0.906 ms w/copy,   0.576 ms kernel | gpu f32   0.320 /   0.120 | f64 exact-vs-cpu maxdiff 2.6875
n=1024  cpu(java f64)  1245.76 ms | gpu f64   5.720 ms w/copy,   4.432 ms kernel | gpu f32   1.625 /   0.859 | f64 exact-vs-cpu maxdiff 1.5625
n=2048  cpu(java f64) 16346.68 ms | gpu f64  40.541 ms w/copy,  35.238 ms kernel | gpu f32   9.360 /   6.740 | f64 exact-vs-cpu maxdiff 1.5

-- element-wise add (memory bound), f64 --
n=4096      cpu   0.037 ms | gpu   0.023 ms w/copy |   0.008 ms kernel
n=65536     cpu   0.513 ms | gpu   0.112 ms w/copy |   0.008 ms kernel
n=1048576   cpu   7.419 ms | gpu   1.579 ms w/copy |   0.041 ms kernel
n=16777216  cpu   9.003 ms | gpu  23.467 ms w/copy |   1.667 ms kernel

-- pure launch overhead (16x16 gemm, sync each) --
launch+sync 8.1 us | launch only (async) 3.7 us
```

The `cpu(java f64)` column is a plain triple loop and is NOT JIT-warm at the small end,
so it flatters the GPU there. `matmul-baseline.lisp` is the honest CPU number and the
one the todo quotes; the element-wise `n=16777216` row is the useful negative result --
128 MB of operands, and the copies lose to the CPU outright.

```
$ java PtxSpike.java
(1) driver JIT of compute_75 PTX onto sm_121: 25.9 ms -- no nvrtc, no toolkit

(2) memory routes, n=1024 f64 gemm
    device alloc + HtoD/DtoH (double copy: heap->native->device)   6.028 ms  [C[0]=0.219]
    cuMemAllocManaged (one copy: heap->managed, GPU reads it)       5.546 ms  [C[0]=0.219]
    ... same buffers, already resident (kernel only)                4.441 ms
    pinned host + DEVICEMAP (GPU reads host memory directly)        5.450 ms  [C[0]=0.219]

(3) cuBLAS comparison
    n=1024  f32: cuBLAS   0.127 ms (16891.1 GFLOP/s) | tiled PTX kernel   0.860 ms (6.8x)
    n=1024  f64: cuBLAS   5.116 ms ( 419.7 GFLOP/s) | tiled PTX kernel   4.432 ms (0.9x)
    n=2048  f32: cuBLAS   0.918 ms (18719.9 GFLOP/s) | tiled PTX kernel   6.708 ms (7.3x)
    n=2048  f64: cuBLAS  40.866 ms ( 420.4 GFLOP/s) | tiled PTX kernel  35.183 ms (0.9x)
```

Line (1) is the single most important line in this directory: `compute_75` PTX, six
generations older than the card, JIT-compiled by the driver alone. That is what lets the
feature ship without a CUDA toolkit on the user's machine. 25.9 ms is the COLD number;
the driver keeps its own on-disk compute cache (`~/.nv/ComputeCache`), so a re-run of
the same PTX reports **1.3 ms**. Since the resource is a fixed, checked-in text, every
run after a user's first one is the cached one -- so the load cost is a startup
non-issue and needs no `cuModuleLoadDataEx` cache plumbing of our own.

```
$ java Tf32Check.java
cublasGetMathMode = 0  (0 = CUBLAS_DEFAULT_MATH, 1 = TF32_TENSOR_OP, 3 = PEDANTIC)
input   1+2^-20 = 1.000000954 (bits 3f800008)
cuBLAS  C[0]    = 1.000000954 (bits 3f800008)
=> low bit SURVIVED: genuine FP32, not TF32
```

```
$ java CublasEndToEnd.java
f32  (ms/call)
    n     ours+copy  cuBLAS+copy  ratio |  ours res.  cuBLAS res.  ratio
    256       0.080        0.067    1.2x |     0.030        0.017    1.8x
    512       0.269        0.184    1.5x |     0.121        0.034    3.6x
    1024      1.404        0.672    2.1x |     0.861        0.127    6.8x
    2048      8.799        2.970    3.0x |     6.752        0.927    7.3x
f64  (ms/call)
    n     ours+copy  cuBLAS+copy  ratio |  ours res.  cuBLAS res.  ratio
    256       0.167        0.206    0.8x |     0.087        0.125    0.7x
    512       0.858        0.990    0.9x |     0.576        0.707    0.8x
    1024      5.433        6.143    0.9x |     4.433        5.116    0.9x
    2048     39.579       44.922    0.9x |    35.243       40.875    0.9x
```

This is the probe that settles the cuBLAS question, and it settles it against cuBLAS:
at f64 -- the default `linalg` width -- the naive tiled kernel is 10-25% FASTER, and at
f32 the famous 7x shrinks to 1.2-3.0x once the copies phase 1 cannot avoid are on the
clock. Against that: `libcublas.so.13` is 59 MB and links `libcublasLt.so.13` at 601 MB
(`ldd` confirms the link), so the price is a 660 MB toolkit requirement.

```
$ java ResidencySpike.java
-- batched rank-3 matmul, the shape --simd never intercepts --
    b*h=24   n=64   d=32   java f64      27.2 ms | gpu   0.025 ms (1078x, 249 GFLOP/s)
    b*h=48   n=256  d=64   java f64     350.4 ms | gpu   0.187 ms (1873x, 2152 GFLOP/s)
    b*h=192  n=512  d=64   java f64    2778.3 ms | gpu   2.679 ms (1037x, 2405 GFLOP/s)

-- the residency question: (x@w1 + b) -> tanh -> (@w2 + b2) --
    n=128   resident (1 up, 5 kernels, 1 down)   0.051 ms | per-op round trip   0.192 ms (3.7x worse)
    n=512   resident (1 up, 5 kernels, 1 down)   0.369 ms | per-op round trip   1.012 ms (2.7x worse)
    n=1024  resident (1 up, 5 kernels, 1 down)   2.218 ms | per-op round trip   4.441 ms (2.0x worse)
```

The `java f64` column here is an unwarmed single-batch loop scaled by the batch count --
an order of magnitude, not a measurement. The right-hand pair is the real result, and it
is what demoted residency from precondition to phase 3.

```
$ java TinySpike.java
one intercepted linalg:matmul, host->device->kernel->host, f64:
      8x8   @   8x8      18.0 us
     32x8   @   8x8      16.4 us
     32x32  @  32x32     19.7 us
     64x64  @  64x64     23.7 us
    128x128 @ 128x128    56.0 us
```

```
$ java --add-modules jdk.incubator.vector Mm2     # matmul-baseline.lisp, JVM --simd
                            BEFORE todo-469          AFTER todo-469 (5a3e8f16)
n=32   f64 / f32            0.150 / 0.050            0.100 / 0.100
n=64   f64 / f32            0.450 / 0.400            0.450 / 0.400
n=128  f64 / f32            0.500 / 0.750            1.400 / 0.850
n=256  f64 / f32            2.800 / 5.200            2.800 / 1.450
n=512  f64 / f32           21.200 / 39.800          21.400 / 10.900
```

The f32 column moved 3.7x at n=512 and the widths inverted, so the todo quotes the AFTER
column and the GPU's f32 margin there is 34x rather than the 124x the BEFORE column
implied. `#d` did not move, as designed.

Note what the last two blocks say together: the GPU's ~16-18 us floor is flat, and
`--simd` costs 0.1-0.45 ms at n=32-64 -- so below n~64 the GPU is not beating CPU
arithmetic, it is beating rontolisp's own per-call overhead. That win is real but
fragile, and it is why the size threshold must be measured on the target machine rather
than hardcoded from FLOP counts.

```
$ java --add-modules jdk.incubator.vector MatmulFProbe.java     # random zero-mean inputs
f32 lanes=4, f64 lanes=2
      laneF32 vs oracle: max 701460 ulp, max rel 0.0428
n=256  scalarAcc   5.06 ms | laneF2D  930.74 ms (bit-identical) | laneF32   1.37 ms (DIFFERS) | f64   2.49 ms
      laneF32 vs oracle: max 440342 ulp, max rel 0.0310
n=512  scalarAcc  39.01 ms | laneF2D 7477.00 ms (bit-identical) | laneF32  10.38 ms (DIFFERS) | f64  19.52 ms

$ java --add-modules jdk.incubator.vector W                     # width-baseline.lisp, JVM --simd
matmul n=256 f64 2.550   f32 5.100      <- the anomaly: f32 2x SLOWER
matmul n=512 f64 20.250  f32 39.850
add   1d f64 1.100       f32 0.620      <- everything else behaves
exp   1d f64 2.420       f32 2.100
dot   1d f64 0.240       f32 0.280
sum   1d f64 0.280       f32 0.280
```

`scalarAcc` is today's kernel, `laneF2D` is the wasm backend's approach ported to the
JVM, `laneF32` is f32 lanes with an f32 accumulator. Two results decided todo-469:
`convert(F2D)` is **190x** slower than the scalar loop it would replace, so wasm's
bit-identical trick cannot come to the JVM; and `laneF32` is 2.8x faster than the f64
kernel but differs from the oracle by up to 3-4% relative on the worst (near-zero,
post-cancellation) cell. The probe's f64 column reproduces rontolisp's own 20.25 ms, so
it is measuring the right kernel. Run it with the DYADIC inputs it originally had and
`laneF32` reports "bit-identical" -- an artifact of test data that round-trips exactly,
which is why the committed version uses zero-mean random values.

## What the Metal probes printed

Same rule: verbatim, so a later run can be diffed against it. Apple M4 Max, macOS 26.3.1.
The rontolisp `--simd` baseline these are measured against, on the SAME machine, is at the
bottom.

```
$ java MtlF64Probe.java
device: Apple M4 Max
  supportsFamily 1001 (Apple1) = yes
  supportsFamily 1002 (Apple2) = yes
  supportsFamily 1003 (Apple3) = yes
  supportsFamily 1004 (Apple4) = yes
  supportsFamily 1005 (Apple5) = yes
  supportsFamily 1006 (Apple6) = yes
  supportsFamily 1007 (Apple7) = yes
  supportsFamily 1008 (Apple8) = yes
  supportsFamily 1009 (Apple9) = yes
  double             REJECTED: MSL compile failed: program_source:3:28: error: 'double' is not supported in Metal kernel void k(device const double* a, device double* b, uint i [[thread_position_in_grid]]) {                            ^ program_source:3:46: error: 'double' is not supported in Metal kernel void k(device const doub...
  float              COMPILES
  half               COMPILES
  bfloat             COMPILES
  long (64-bit int)  COMPILES

$ java MtlSpike.java
device: Apple M4 Max  unified=1  workingSet=110100 MB  maxTG=1024
MSL compile (newLibraryWithSource): 2.3 ms | pipeline: 1.1 ms
n=64    cpu(java f64)     0.60 ms | gpu f32   0.191 ms w/copy,   0.179 ms kernel | dyadic-input check: matches f64 oracle exactly
n=128   cpu(java f64)     2.46 ms | gpu f32   0.217 ms w/copy,   0.201 ms kernel | dyadic-input check: matches f64 oracle exactly
n=256   cpu(java f64)    10.17 ms | gpu f32   0.270 ms w/copy,   0.260 ms kernel | dyadic-input check: matches f64 oracle exactly
n=512   cpu(java f64)   111.76 ms | gpu f32   0.836 ms w/copy,   0.792 ms kernel | dyadic-input check: matches f64 oracle exactly
n=1024  cpu(java f64)   984.71 ms | gpu f32   1.378 ms w/copy,   1.180 ms kernel | dyadic-input check: matches f64 oracle exactly
n=2048  cpu(java f64) 19435.25 ms | gpu f32   8.832 ms w/copy,   8.070 ms kernel | dyadic-input check: matches f64 oracle exactly

-- element-wise add (memory bound), f32 --
n=4096       cpu   0.027 ms | gpu   0.125 ms w/copy |   0.121 ms kernel
n=65536      cpu   0.421 ms | gpu   0.145 ms w/copy |   0.127 ms kernel
n=1048576    cpu   1.500 ms | gpu   0.369 ms w/copy |   0.149 ms kernel
n=16777216   cpu  13.501 ms | gpu   4.175 ms w/copy |   0.775 ms kernel

-- pure launch overhead (16x16 gemm) --
encode+commit+wait 69.5 us | encode+commit only (async) 6.0 us

$ java MtlTiny.java
one intercepted linalg:matmul, heap->buffer->kernel->heap, f32:
      8x8   @   8x8     113.9 us
     32x8   @   8x8      89.8 us
     32x32  @  32x32     91.7 us
     64x64  @  64x64     84.7 us
    128x128 @ 128x128    84.6 us
    256x256 @ 256x256   119.7 us

wait strategy, empty-ish 16x16 dispatch:
    encode only                   4.4 us
    encode + waitUntilCompleted   81.4 us
    encode + spin on status       79.1 us

N dispatches inside ONE command buffer (16x16 each), us per dispatch:
      1 dispatches:    92.4 us total,  92.38 us each
      2 dispatches:   105.4 us total,  52.69 us each
      5 dispatches:   129.5 us total,  25.89 us each
     10 dispatches:   157.6 us total,  15.76 us each
     50 dispatches:   435.1 us total,   8.70 us each

$ java MtlResidency.java
-- batched rank-3 matmul, the shape --simd never intercepts --
    b*h=24   n=64   d=32   gpu    0.169 ms (37 GFLOP/s)
    b*h=48   n=256  d=64   gpu    0.355 ms (1133 GFLOP/s)
    b*h=192  n=512  d=64   gpu    3.684 ms (1749 GFLOP/s)

-- the residency question: (x@w1 + b) -> tanh -> (@w2 + b2), f32 --
    n=128   resident 1 cmdbuf   0.169 ms | resident, 5 cmdbufs   0.615 ms (3.6x) | per-op round trip   0.596 ms (3.5x)
    n=512   resident 1 cmdbuf   0.405 ms | resident, 5 cmdbufs   0.850 ms (2.1x) | per-op round trip   1.118 ms (2.8x)
    n=1024  resident 1 cmdbuf   2.271 ms | resident, 5 cmdbufs   2.766 ms (1.2x) | per-op round trip   3.697 ms (1.6x)

$ java MtlPrecision.java
MTLCompileOptions defaults: mathMode=2  (0=?, 1=safe, 2=relaxed, 3=fast)  fastMathEnabled=1
  default options  n=128   gpu-f32 vs f64 oracle: maxrel 4.54e-07 | cpu-f32 vs f64 oracle: maxrel 3.72e-07 | gpu-f32 vs cpu-f32: maxrel 2.44e-07
  default options  n=512   gpu-f32 vs f64 oracle: maxrel 8.52e-07 | cpu-f32 vs f64 oracle: maxrel 8.65e-07 | gpu-f32 vs cpu-f32: maxrel 2.81e-07
  default options  tanh vs Math.tanh over 4096 gaussians: max abs 1.19e-07, max rel 4.87e-05, 2002/4096 cells differ
  mathMode=1       n=128   gpu-f32 vs f64 oracle: maxrel 4.54e-07 | cpu-f32 vs f64 oracle: maxrel 3.72e-07 | gpu-f32 vs cpu-f32: maxrel 2.44e-07
  mathMode=1       n=512   gpu-f32 vs f64 oracle: maxrel 8.52e-07 | cpu-f32 vs f64 oracle: maxrel 8.65e-07 | gpu-f32 vs cpu-f32: maxrel 2.81e-07
  mathMode=1       tanh vs Math.tanh over 4096 gaussians: max abs 1.19e-07, max rel 4.87e-05, 2002/4096 cells differ
  mathMode=3       n=128   gpu-f32 vs f64 oracle: maxrel 4.54e-07 | cpu-f32 vs f64 oracle: maxrel 3.72e-07 | gpu-f32 vs cpu-f32: maxrel 2.44e-07
  mathMode=3       n=512   gpu-f32 vs f64 oracle: maxrel 8.52e-07 | cpu-f32 vs f64 oracle: maxrel 8.65e-07 | gpu-f32 vs cpu-f32: maxrel 2.81e-07
  mathMode=3       tanh vs Math.tanh over 4096 gaussians: max abs 1.19e-07, max rel 4.87e-05, 2002/4096 cells differ

$ java MtlMps.java
MPSMatrixMultiplication class = true

f32 n x n gemm, ms per call
n       ours resident   MPS resident    ours + copy     MPS + copy
128          0.156 ms       0.192 ms       0.165 ms       0.199 ms   (MPS 0.8x ours; agree to 0.0)
256          0.236 ms       0.213 ms       0.264 ms       0.208 ms   (MPS 1.1x ours; agree to 0.0)
512          0.741 ms       0.287 ms       0.304 ms       0.228 ms   (MPS 2.6x ours; agree to 0.0)
1024         1.129 ms       0.335 ms       1.387 ms       0.586 ms   (MPS 3.4x ours; agree to 0.0)
2048         9.363 ms       1.717 ms      10.179 ms       2.486 ms   (MPS 5.5x ours; agree to 0.0)

$ java MtlMpsDiff.java
n=256   differing cells 0/65536 | ours vs oracle 6.31e-07 | MPS vs oracle 6.31e-07 | ours vs MPS 0.00
n=1024  differing cells 0/1048576 | ours vs oracle 1.28e-06 | MPS vs oracle 1.28e-06 | ours vs MPS 0.00

$ java AccelerateProbe.java
Accelerate cblas, ms per n x n gemm (single thread of control, library may thread):
n         dgemm f64    sgemm f32 java f64 loop
64         0.004 ms     0.002 ms      60.7 ms   (146 / 242 GFLOP/s)
128        0.012 ms     0.005 ms       1.7 ms   (350 / 839 GFLOP/s)
256        0.073 ms     0.025 ms      10.6 ms   (458 / 1340 GFLOP/s)
512        0.331 ms     0.096 ms     111.0 ms   (811 / 2795 GFLOP/s)
1024       2.833 ms     0.819 ms     962.4 ms   (758 / 2623 GFLOP/s)
2048      21.852 ms     5.446 ms       NaN ms   (786 / 3155 GFLOP/s)

$ java MtlCompileCost.java   # three consecutive runs
MTLCreateSystemDefaultDevice   13.9 ms | newLibraryWithSource    2.6 ms | same source again    0.1 ms | 1st pipeline   0.9 ms | 2nd pipeline   0.1 ms
MTLCreateSystemDefaultDevice   12.2 ms | newLibraryWithSource    2.7 ms | same source again    0.1 ms | 1st pipeline   0.9 ms | 2nd pipeline   0.1 ms
MTLCreateSystemDefaultDevice   14.7 ms | newLibraryWithSource    2.9 ms | same source again    0.1 ms | 1st pipeline   0.9 ms | 2nd pipeline   0.1 ms
```

### The five lines that matter

1. **`'double' is not supported in Metal`** -- from the compiler, not from a benchmark.
   CUDA's fp64 is 44x slower than its fp32 but it exists; MSL has no double at all, and
   `half` / `bfloat` / 64-bit int all compile fine, so this is a deliberate omission in the
   language. `linalg`'s default element type is double-float, so on Apple a `--gpu` is not
   "f32 is where the win is" -- it is "f32 or nothing".

2. **`newLibraryWithSource` costs 2.3 ms warm and needs no toolchain.** There is no Xcode
   on this machine (`xcrun metal` is absent) and MSL still compiled at run time, because
   the compiler lives in the OS. That is strictly better than the PTX story -- no
   build-time artifact to generate, check in or version against a virtual architecture.
   32 ms on the first ever run, ~2.5 ms afterwards (the OS caches across processes, like
   `~/.nv/ComputeCache`), and 0.1 ms for the same source a second time in-process. The
   real startup cost is `MTLCreateSystemDefaultDevice` at 12-15 ms, which is what the
   availability probe would pay.

3. **The per-call floor is ~85 us, five times CUDA's 16-18 us**, and it is flat from 8x8
   to 128x128 exactly as CUDA's was. Spinning on `[cb status]` instead of blocking in
   `waitUntilCompleted` changes nothing (83.1 vs 81.1 us), so this is the round trip
   itself, not the blocking primitive -- there is no cheaper wait to find. But the cost is
   per COMMAND BUFFER, not per dispatch: 50 dispatches in one command buffer cost 8.8 us
   each. Batching is worth 10x, and it is the same mechanism residency needs.

4. **MPS is 5.5x the naive kernel at n=2048, and bit-identical to it.** Zero differing
   cells out of 1,048,576 at n=1024, both landing 1.28e-6 from the f64 oracle -- verified
   with a poisoned output buffer so this is agreement, not a silent no-op. Unlike cuBLAS
   it ships in the OS, so it costs no dependency and no toolkit. Both halves of the cuBLAS
   verdict invert here.

5. **Accelerate's CPU BLAS beats both of them below n~1024, has a double, and has no 85 us
   floor.** 800 GFLOP/s at f64 -- nearly twice the GB10's cuBLAS DGEMM (420) -- and 3200
   GFLOP/s at f32, which is faster than our Metal kernel at every size measured. It is
   plain C, in the OS, reachable in four lines of FFM. Still 35-121x `--simd` AFTER
   todo-469 gave the f32 kernel its lanes, so that landing does not dent it. See
   `../123-gpu-acceleration.md` for what it does to the plan, and `../../.kb/linalg-blas.md` for the
   item it became.

### The width probe, same machine

`.kb/linalg-simd.md` cites `MatmulFProbe` and warns that it answers differently per
architecture. It does, and the two aarch64 machines are not interchangeable either:

```
$ java --add-modules jdk.incubator.vector MatmulFProbe.java     # Apple M4 Max
f32 lanes=4, f64 lanes=2
      laneF32 vs oracle: max 701460 ulp, max rel 0.0428
n=256  scalarAcc   4.82 ms | laneF2D  557.52 ms (bit-identical) | laneF32   1.26 ms (DIFFERS) | f64   2.34 ms
      laneF32 vs oracle: max 440342 ulp, max rel 0.0310
n=512  scalarAcc  35.86 ms | laneF2D 4473.65 ms (bit-identical) | laneF32   9.71 ms (DIFFERS) | f64  18.07 ms
```

Same ranking as the GB10 run recorded above -- `laneF32` wins, `laneF2D` is catastrophic,
and the relative error against the oracle is identical to five digits because that is a
property of the arithmetic and not of the machine -- but the magnitudes differ. Re-running
the probe on the GB10 to check that the `.kb` row really was that machine's confirms it:
`n=512  scalarAcc 38.97 | laneF2D 7325.79 | laneF32 10.53 | f64 19.98`, against the M4's
35.86 / 4473.65 / 9.71 / 18.07. The two aarch64 machines differ by 1.6x on `laneF2D`, so
the row that was labelled "M4" was the GB10's; the `.kb` table now carries both, named.

### The same probe on the GB10, which is why it now prints a verdict

`AccelerateProbe` walks a candidate list so it runs on both machines. On the DGX Spark it
found something -- and that turned out to be the trap:

```
bound CBLAS: libblas.so.3
identifies as: no tuned-implementation marker found -- possibly the netlib reference; read the verdict
cblas, ms per n x n gemm (single thread of control, library may thread):
n         dgemm f64    sgemm f32 java f64 loop
64         0.070 ms     0.071 ms      79.7 ms   (7 / 7 GFLOP/s)
128        0.621 ms     0.541 ms       1.3 ms   (7 / 8 GFLOP/s)
256        4.517 ms     4.786 ms      11.1 ms   (7 / 7 GFLOP/s)
512       35.285 ms    36.125 ms     142.1 ms   (8 / 7 GFLOP/s)
1024     283.099 ms   354.779 ms    1236.5 ms   (8 / 6 GFLOP/s)
2048    2478.494 ms  2506.586 ms       NaN ms   (7 / 7 GFLOP/s)
```

7-8 GFLOP/s against Accelerate's 800, flat across every size, and f32 no faster than f64:
that is the **netlib reference implementation**, which is a specification written in
Fortran and not a tuned kernel. Confirmed rather than inferred -- `dpkg -S` answers
`libblas3:arm64`, Debian's reference package, and `update-alternatives` shows it as the
only provider at priority 10. Read it against the same machine's `--simd` column below
-- n=256 2.800 ms and n=512 21.200 ms -- and the result is that **the BLAS is 1.6x SLOWER
than the kernel rontolisp already has**. It beats only the naive triple loop.

Three things follow, and they are why the probe gained an `identify()` and a verdict line:

- **Finding a CBLAS is not finding a tuned BLAS**, and the earlier framing ("Linux ships
  none with the base system but very often carries one anyway") was too generous: carrying
  one very often means carrying the reference.
- **The soname cannot tell them apart.** Debian's `libblas.so.3` is an `update-alternatives`
  symlink that points at OpenBLAS when one is installed and at the reference when not, so
  a name-ordered candidate list is not a safety mechanism. The probe now looks for the
  marker symbols tuned implementations export (`openblas_get_config`, `mkl_get_version`,
  `bli_info_get_version_str`, ...) and measures anyway.
- **A `--blas` that bound whatever it found would be a silent 1.6x regression** on this
  machine, at `linalg`'s default width. That is a much worse failure than declining.

This is where an earlier draft of this file went wrong, and the mistake is worth keeping
visible because it is easy to make. It argued that installing OpenBLAS and re-measuring
was "the wrong experiment", on the grounds that the spike's founding rule -- **the runtime
requirement is what the OS or the driver already provides, and nothing the user installs**
-- forbids requiring such a machine. The rule is real and still governs what may be
REQUIRED. It says nothing about what may be MEASURED. Refusing to measure the
tuned-BLAS-on-Linux case meant leaving the size of the opportunistic tier unknown while
simultaneously complaining that it had no representative machine.

So it was measured.

### With a tuned BLAS installed: what the opportunistic tier is actually worth

`libopenblas0-pthread` 0.3.26 installed on the Spark (see the machine-changes log at the
end of this file). The probe tries `libopenblas.so.0` before `libblas.so.3`, so it binds
OpenBLAS directly regardless of what `update-alternatives` points at:

```
=== all threads (nproc=20) ===
bound CBLAS: libopenblas.so.0
identifies as: OpenBLAS (exports openblas_get_config)
n         dgemm f64    sgemm f32
64         0.010 ms     0.006 ms   (52 / 89 GFLOP/s)
128        0.028 ms     0.017 ms   (148 / 247 GFLOP/s)
256        0.245 ms     0.134 ms   (137 / 250 GFLOP/s)
512        1.073 ms     0.514 ms   (250 / 522 GFLOP/s)
1024       5.942 ms     2.943 ms   (361 / 730 GFLOP/s)
2048      43.921 ms    21.492 ms   (391 / 799 GFLOP/s)
verdict: 361 GFLOP/s f64 at n=1024 -- TUNED.

=== OPENBLAS_NUM_THREADS=1 ===
64         0.015 ms     0.009 ms   (34 / 61 GFLOP/s)
128        0.068 ms     0.034 ms   (62 / 122 GFLOP/s)
256        0.523 ms     0.258 ms   (64 / 130 GFLOP/s)
512        4.137 ms     2.030 ms   (65 / 132 GFLOP/s)
1024      33.002 ms    16.227 ms   (65 / 132 GFLOP/s)
2048     265.831 ms   128.435 ms   (65 / 134 GFLOP/s)
verdict: 65 GFLOP/s f64 at n=1024 -- TUNED.
```

Three readings, and the third is the uncomfortable one.

- **Against `--simd` on the same machine** (n=512: f64 21.4 ms, f32 10.9 ms), OpenBLAS is
  **20x threaded / 5.2x on one thread** at f64, and 21x / 5.4x at f32. So the
  opportunistic tier is worth having -- this is not the marginal case that would have let
  the BLAS item stay macOS-shaped. But note which number is which: 20x is 20 CORES, and
  rontolisp is single-threaded today, so binding a threaded BLAS silently makes
  `linalg:matmul` multi-threaded. 5.2x is the honest no-surprises figure and 20x is the
  one that comes with a semantic change.
- **Against Accelerate**, Grace's 391 GFLOP/s f64 across 20 cores is half the M4 Max's
  800. Apple's advantage is not merely that the library is guaranteed present.
- **Against the GPU on the same box, at f64, it is a TIE.** `--gpu` with copies does
  n=512 in 0.906 ms, n=1024 in 5.720, n=2048 in 40.541; threaded OpenBLAS does 1.073,
  5.942, 43.921. Within 20%, 4% and 8%. The GB10's fp64 is weak enough (420 GFLOP/s even
  from cuBLAS) that 20 Grace cores catch it. At f32 the GPU keeps a real but modest
  phase-1 lead: 1.6x at n=512, 2.3x at n=2048.

That last point belongs to todo-123 rather than to the BLAS item, and it sharpens what
`--gpu` is for on THIS class of machine: not f64 -- where a CPU BLAS the user may already
have is level with it -- but f32, residency, and the batched rank-3 shape. It is the same
conclusion Apple reached by a different route (there, MSL simply has no double), and it is
further evidence for phase 0.

The opportunistic tier now has one measured member on each side. Note that cuBLAS is NOT
part of the driver (here it comes from `libcublas-13-0`, pulled by `cuda-libraries-13-0`),
but it IS preinstalled and on the `ldconfig` path on any machine with the CUDA stack, so
its marginal cost on such a box is zero -- exactly like OpenBLAS on a machine that already
has numpy. If that tier is ever built it should be built once for both CPU and GPU, not
twice.

### The CPU baseline, same machine

Measured twice, because todo-469 landed between the two runs and it moves the f32 column
the GPU is compared against.

```
$ java --add-modules jdk.incubator.vector Mm2     # matmul-baseline.lisp, JVM --simd
                            BEFORE todo-469          AFTER todo-469 (5a3e8f16)
n=32   f64 / f32            0.100 / 0.050            0.050 / 0.100
n=64   f64 / f32            0.150 / 0.150            0.150 / 0.050
n=128  f64 / f32            0.600 / 0.700            0.550 / 0.300
n=256  f64 / f32            2.900 / 5.650            2.600 / 1.450
n=512  f64 / f32           25.100 / 41.600          22.100 / 11.350

$ java --add-modules jdk.incubator.vector W       # width-baseline.lisp, JVM --simd
                            BEFORE                   AFTER
matmul n=256  f64 / f32     5.100 / 24.650           2.500 / 1.500
matmul n=512  f64 / f32    68.300 / 86.850          20.050 / 11.350
add   1d      f64 / f32     0.540 / 0.300            0.300 / 0.200
dot   1d      f64 / f32     0.220 / 0.280            0.180 / 0.060
exp   1d      f64 / f32     7.660 / 2.180            1.360 / 1.240
sum   1d      f64 / f32     0.100 / 0.140            0.140 / 0.140
```

`matmul-baseline` is the honest CPU number and the one the todo quotes; `width-baseline`
drives its matmuls through a `funcall`ed lambda with a shorter warm-up, which is why its
BEFORE numbers are so much higher (its AFTER numbers agree with `matmul-baseline`, which
is itself worth noticing -- the old kernel was the thing that made the lambda path look
pathological). The `#f` anomaly the BEFORE column shows was not GB10-specific and was in
fact worse here -- 4.8x slower than `#d` at n=256 against 2x there -- and todo-469 has
since inverted it on this machine too: `#f` is now about 2x FASTER than `#d`. Every
Apple-side comparison below quotes the AFTER column.

## What is deliberately missing

- **No cross-platform probe.** The CUDA files need an NVIDIA driver, the Metal files need
  macOS, and neither set is guarded -- running the wrong half fails at the first
  `libraryLookup`. A real `am.ik.gpu` needs one availability probe that answers "no
  device" without throwing on either platform.
- **No Metal batched MPS.** `MPSMatrixMultiplication` has a batched descriptor
  (`matrixDescriptorWithRows:columns:matrices:rowBytes:matrixBytes:dataType:`) that would
  be the tuned counterpart to `gemm3_f32`; only the naive batched kernel was measured.
- **No Metal object lifetime worth the name.** `Mtl.release` exists and is called on
  buffers; every other `new*`/`alloc` result -- libraries, pipelines, queues, MPS objects,
  the `NSString`s built for every selector argument -- leaks, and there is one
  `autoreleasePoolPush`/`Pop` around each `main`. Real code needs the ownership rules
  (`new`/`alloc`/`copy` are +1, everything else is autoreleased) applied deliberately.
- **No Metal error path.** A failed `newLibraryWithSource:` is turned into an exception
  with the compiler's diagnostics, which is right for a probe and wrong for `--gpu`, which
  must decline to the CPU instead of signalling. Nothing checks
  `[commandBuffer error]` at all.
- **No error handling worth the name.** `Cu.check` exists; most call sites ignore the
  status. Real code needs a `CUresult` table (`silicon-cuda`'s `CUResult.java` is 685
  lines of exactly that) and a decline-on-error path, since `--gpu` must degrade to the
  CPU rather than signal.
- **No lifetime management.** Buffers are freed on the happy path only, and the primary
  context is retained and never released.
- **No `linalg` integration at all.** Nothing here touches a `LispFloatArray`, a decline
  sentinel or a call site. That is phase 1, and it is the point at which this directory
  stops being useful.

## Changes made to the spike machines

The probes here are read-only with one exception, recorded so the machines can be put
back and so a later reader knows which numbers came from a modified system.

### DGX Spark (`gx10-c90e`, GB10, Ubuntu 24.04 aarch64) -- 2026-08-20

**One package installed**, to measure what a tuned CPU BLAS is worth on Linux (the
"With a tuned BLAS installed" section above). Nothing else was changed; no dependency was
pulled and nothing was removed.

```bash
sudo apt-get install -y libopenblas0-pthread     # 0.3.26+ds-1ubuntu0.1
```

State before, as recorded by the probes themselves earlier that day:

```
libblas3:arm64        3.12.0-3build1.1     <- Debian's netlib reference, the ONLY provider
liblapack3:arm64      3.12.0-3build1.1
libopenblas0-pthread  not installed
update-alternatives:  libblas.so.3 -> /usr/lib/aarch64-linux-gnu/blas/libblas.so.3 (priority 10)
AccelerateProbe:      bound libblas.so.3, 7-8 GFLOP/s, no tuned marker
```

State after:

```
libopenblas0-pthread:arm64  0.3.26+ds-1ubuntu0.1
update-alternatives:  libblas.so.3 -> .../openblas-pthread/libblas.so.3 (priority 100, auto)
AccelerateProbe:      bound libopenblas.so.0, 361 GFLOP/s f64 threaded / 65 single
```

**The side effect is the alternatives switch**, not the package: `libblas.so.3` now
resolves to OpenBLAS for everything on the machine that links BLAS by that soname (numpy,
scipy, R, ...). That is normally an improvement -- they go from the reference
implementation to a tuned one -- but it IS a system-wide behaviour change and it was not
asked for by anything in this directory. The probe itself does not depend on it: its
candidate list tries `libopenblas.so.0` before `libblas.so.3`.

To revert completely:

```bash
sudo apt-get remove libopenblas0-pthread     # alternatives falls back to priority 10
update-alternatives --display libblas.so.3-aarch64-linux-gnu   # verify
```

To keep OpenBLAS installed but restore the reference as the system default:

```bash
sudo update-alternatives --set libblas.so.3-aarch64-linux-gnu \
     /usr/lib/aarch64-linux-gnu/blas/libblas.so.3
```

Two things that were NOT changed and are worth knowing: the CUDA stack was already
present (`libcublas-13-0` via `cuda-libraries-13-0`, on the `ldconfig` path) and no NVPL
is installed. `git pull` was run in `/home/maki/git/rontolisp` and the tree is otherwise
clean.

### Apple M4 Max (macOS 26.3.1) -- 2026-08-20

**Nothing installed.** That is the point of the Metal result: `newLibraryWithSource`
compiled MSL on a machine with no Xcode and no `xcrun metal`, and Accelerate and
MetalPerformanceShaders are part of the OS. The only files produced were the
`Mm2.class` / `W.class` baselines and a `native-image` build directory, all deleted.
