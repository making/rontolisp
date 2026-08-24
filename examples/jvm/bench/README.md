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
