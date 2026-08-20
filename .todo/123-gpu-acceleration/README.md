# The `--gpu` feasibility spike (2026-08-20)

Throwaway probes kept for reproducibility, NOT project code: they are outside `src/`, are
not in the reactor, are not formatted by `spring-javaformat:apply`, and nothing builds or
tests them. They exist so that the numbers in `../123-gpu-acceleration.md` can be
re-derived on other hardware -- especially the two decisions those numbers drove
(per-call intercept before residency; PTX-in-the-jar instead of a runtime toolkit).

Every file is a single-class JDK source-launcher program: no build step, no dependency,
Java 22+ for the FFM API. `--enable-native-access=ALL-UNNAMED` silences the restricted-
method warning.

## The machine these numbers came from

NVIDIA GB10 (Grace Blackwell, `sm_121`, 48 SMs, unified addressing + managed memory),
aarch64, driver 580.173.02 / CUDA 13.0, Oracle GraalVM 25.0.4. `nvidia-smi` and
`/usr/local/cuda` present. A different device changes every number below; what should
survive is the SHAPE of each result.

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
| `matmul-baseline.lisp` | the CPU side of the comparison -- `linalg:matmul` under `--simd`, warm, 20 reps. Not a GPU program. |

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

# PtxSpike needs the PTX first; 75 is CUDA 13's oldest accepted virtual arch
java --enable-native-access=ALL-UNNAMED DumpPtx.java 75
java --enable-native-access=ALL-UNNAMED PtxSpike.java
```

`Cu.java` and `MatmulSpike.java` are picked up automatically by the source launcher --
do not pass them as arguments, or they land in `args` instead (that mistake is why
`DumpPtx` first appeared to reject every `--gpu-architecture`).

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
n=32 f64 0.150 ms/call      n=32 f32 0.050 ms/call
n=64 f64 0.450 ms/call      n=64 f32 0.400 ms/call
n=128 f64 0.500 ms/call     n=128 f32 0.750 ms/call
n=256 f64 2.800 ms/call     n=256 f32 5.200 ms/call
n=512 f64 21.200 ms/call    n=512 f32 39.800 ms/call
```

Note what the last two blocks say together: the GPU's ~16-18 us floor is flat, and
`--simd` costs 0.15 ms at n=32 -- so below n~64 the GPU is not beating CPU arithmetic,
it is beating rontolisp's own per-call overhead. That win is real but fragile, and it is
why the size threshold must be measured on the target machine rather than hardcoded from
FLOP counts.

## What is deliberately missing

- **No Metal probe.** No Apple Silicon was available. The Metal section of the todo is a
  sketch, and phase 5 begins by validating it -- `silicon-metal` in `../../../silicon/`
  is the reference, but it reaches Metal through a Swift shim we cannot ship, so the
  `objc_msgSend`-over-FFM route is untested by anything here.
- **No error handling worth the name.** `Cu.check` exists; most call sites ignore the
  status. Real code needs a `CUresult` table (`silicon-cuda`'s `CUResult.java` is 685
  lines of exactly that) and a decline-on-error path, since `--gpu` must degrade to the
  CPU rather than signal.
- **No lifetime management.** Buffers are freed on the happy path only, and the primary
  context is retained and never released.
- **No `linalg` integration at all.** Nothing here touches a `LispFloatArray`, a decline
  sentinel or a call site. That is phase 1, and it is the point at which this directory
  stops being useful.
