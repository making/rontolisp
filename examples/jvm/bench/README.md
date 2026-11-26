# What the packed float array costs at the Java boundary

The measurement that picked the shape of the `:float-vector` / `:float-matrix`
designators. Same 2-norm, same 2^20 doubles, four ways to reach it:

```bash
./run.sh          # builds the kernel library, compiles HandleBench.java, runs it
```

| | ms/call | vs plain Java |
| --- | --- | --- |
| plain Java loop, C2 auto-vectorized | 0.893 | 1.00x |
| kernel on a pre-packed array (the floor) | 0.291 | 3.06x |
| **kernel behind the `RontoFloatArray` handle** | **0.286** | **3.12x** |
| kernel behind a facade that copies a `double[]` per call | 2.584 | 0.35x |

Measured 2026-08-24 on Linux x86_64 (Xeon E5-2697A v4), Oracle GraalVM 25.0.4,
`--simd`, 300 iterations after 3000 warm-up calls.

**Read the last row first.** Marshalling a plain `double[]` at every call would be
the obvious API, and it costs about ten times the kernel it feeds — turning a 3x
win into a 3x loss. That is why the boundary type is a handle the caller *holds*
rather than an array the boundary *converts*: `RontoFloatArray.of(...)` copies once,
`toArray()` copies out once, and every call in between hands the kernel the packed
array it already has.

The handle costs nothing measurable over the raw packed array — one field read —
so this is not a tradeoff to tune. Both middle rows are the same kernel; only the
top row is hand-written Java.

Full contract: [the JVM library guide](../../../doc/en/guides/jvm-library.md).

## Does the handle defeat the `--gpu` device-resident tier?

The second measurement, and the one that needed hardware. A `--gpu` kernel's
result is wrapped **without** materializing, so a Java-side chain
`h = Kernels.step(w, h)` should leave every intermediate on the device and bring
only the last one home — the alternative (materializing at the boundary) is
correct too, and pays a download the next call only re-uploads.

```bash
./run.sh gpu      # builds gpu-kernels.lisp twice, with --gpu and without
```

200 chained GEMVs over a resident 2048x2048 single-float matrix:

| | ms/iteration | uploads | KB moved |
| --- | --- | --- | --- |
| the same chain inside Lisp — no crossing at all | 0.070 | 1 | 8 |
| **the Java chain, one crossing per iteration** | **0.070** | **1** | **8** |
| a chain that materializes at every crossing | 0.098–0.117 | 200 | 1600 |

The boundary costs nothing measurable: the Java chain runs at the speed of the
loop that never leaves Lisp, and uploads the vector once for all 200 iterations
rather than once per iteration. The last row is what materializing in
`RontoBoundary.floatArrayResult` would have cost — 1.4x to 1.7x, and 200x the
traffic.

The other two halves, printed by the same run:

- `toArray()` on a device result moves it home **exactly once** (one dirty copy
  cleared, one stub given a backing; a second read moves nothing), and answers
  the same library compiled without `--gpu` **bit for bit** — the host array
  before that read is 2 elements, the dimension header alone.
- `set(i, v)` through a handle lands on the array the residency guard answers,
  so the next kernel call sees it — both into a lazy result the device still
  holds and into the resident matrix, whose device copy the write invalidates.

Measured 2026-08-24 on an NVIDIA GB10 (aarch64, driver 580.173.02), Oracle
GraalVM 25.0.4.

### The same three on Metal: the handle is free, and not load-bearing

Metal keeps lazy results **off** as a measured policy, so every kernel result
comes home eagerly whether or not it crosses the boundary. That decides all
three rows at once — the same 200 chained GEMVs, on an Apple M4 Max (40-core
GPU, macOS 26.3.1), Oracle GraalVM 25.0.3:

| | ms/iteration | uploads | KB moved |
| --- | --- | --- | --- |
| the same chain inside Lisp — no crossing at all | 0.127–0.142 | 200 | 1600 |
| **the Java chain, one crossing per iteration** | **0.128–0.140** | **200** | **1600** |
| a chain that materializes at every crossing | 0.127–0.149 | 200 | 1600 |

All three are the same number, and the traffic column is the reason: the tier
already pays the round trip the boundary was suspected of adding, and on unified
memory that upload is a memcpy into a shared slab. So the handle costs nothing
here either — but the claim it protects is idle until lazy results pay on this
backend.

The other two halves degenerate the same way, as expected:

- `toArray()` moves **nothing** — dirty 0, backings 0, unchanged across the read
  — because the result was never away: the host array before the read already
  carries all 2048 elements, not the 2-element header. It still answers the
  no-`--gpu` build **bit for bit**.
- `set(i, v)` **into the resident matrix is the half that still bites**: the
  matrix is the one thing Metal does keep on the device, and the write
  invalidates its device copy so the next GEMV sees the new weight. The lazy
  half is an eager result here, and the write lands on it too.
