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
GraalVM 25.0.4. **CUDA only** — the same three measurements on Metal are
outstanding.
