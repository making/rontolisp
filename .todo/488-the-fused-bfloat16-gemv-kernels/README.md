# The fused `bfloat16` GEMV kernels: the both-JIT harness, 2026-09-03

The bench harness for `../488-the-fused-bfloat16-gemv-kernels.md`, and the provisional
numbers it produced on the day the kernels landed.

Unlike `../482-bfloat16-a-narrow-width-that-pays/`, whose probes are standalone
source-launcher programs, this harness measures **the shipped kernels themselves** --
`eval.VecSimdKernels` and `codegen.jvm.JvmSimdVectorTemplate` -- so a number here cannot
drift from what the project actually runs. It is therefore two test-scope `main` classes
(package-private access is the only way to reach the kernels) rather than files in this
directory:

| what | where |
| --- | --- |
| the interpreter kernels | `src/test/java/am/ik/rontolisp/eval/Bf16GemvBench.java` |
| the embedded `--simd` bridge (what a compiled `.class` ships) | `src/test/java/am/ik/rontolisp/codegen/jvm/Bf16TemplateGemvBench.java` |
| the runner, both JITs, labelled | `bench.sh` |

Neither is a surefire test (the naming patterns skip them); `./mvnw test` never runs them.

```bash
./mvnw -o test-compile
.todo/488-the-fused-bfloat16-gemv-kernels/bench.sh both
```

## Why both JITs

`.todo/482` round 2: the spike's fused kernel measured 1.51x of f32 under Graal and
**0.20x** under C2 on identical arithmetic -- the method had overrun C2's inlining budget
for the Vector API chain and every vector was boxed, with no warning, no exception and the
same bits. Graal is what this box, CI and the native image run; C2
(`-XX:-UseJVMCICompiler`) is what a stock OpenJDK runs a compiled `.class` under. A shape
that is fast under one and boxed under the other is not done.

**The cliff did not reproduce here**: the shipped kernels run at 0.85-1.02x under C2 and
0.76-0.82x under Graal, and the 4-accumulator probe reaches 1.97x under C2. The
one-small-method-per-width rule held.

## The finding: the 1.6x is `.todo/480`'s, not this item's

The headline of `.todo/488` -- 1.60x at 4096x4096 -- **does not reproduce against the
shipped kernels, and the reason is not bf16.** The shipped f32 GEMV row is a single
`FloatVector` accumulator with a two-rounding mul-then-add and no FMA. One accumulator is
one dependency chain, and at 5.5-7.6 Gelem/s (1 thread) that chain, not memory, is what
bounds the row. A kernel that is latency-bound has no bandwidth to save, so halving the
weight bytes buys nothing.

**And the equivalence contract forces the single accumulator.** These kernels are safe
because fused == widen-then-f32-kernel bit for bit; giving the bf16 arm four accumulators
while the f32 arm has one changes the fold order and the equivalence -- the whole reason
bf16 needs no new entry in the cross-backend identity contract -- is gone. So the bf16
kernel accumulates exactly the way the f32 kernel does, and it will gain accumulators when
and only when the f32 kernel does.

The 4-accumulator + FMA arm below is a **probe, not shipped code**, kept only to price
that: it is not bit-equal to anything, and `.todo/480` absorbs it. At 4096x4096 it
reproduces the spike almost exactly (1.59x Graal / 1.97x C2 against 1.48x / 2.06x), which
is what makes the diagnosis certain rather than a guess.

**`.todo/480` is a prerequisite of `.todo/488`, not an independent optimization.**

## Provisional numbers (2026-09-03)

**These are a smoke run, not a measurement**: taken while two other lanes were building
and running suites on the same 20 cores, one run per cell, no medians. The
single-threaded columns are stable across runs to ~0.05x; the `--parallel` ones moved by
0.3x between runs and need a quiet window. **Re-measure after `.todo/480` lands** -- every
f32 baseline here changes when it does.

NVIDIA GB10 (Grace Blackwell), aarch64 Cortex-X925, 20 cores, Oracle GraalVM 25.0.4,
`RONTOLISP_THREADS` default (20). f32 activations, bf16 weights narrowed from the same
N(0, 0.02) gaussians the f32 baseline runs over, so both arms multiply the same values.

`eval.VecSimdKernels`, one thread, ms per GEMV:

| shape | Graal f32 | Graal bf16 | Graal ratio | C2 f32 | C2 bf16 | C2 ratio |
| --- | --- | --- | --- | --- | --- | --- |
| 288x288 | 0.010 | 0.013 | 0.78x | 0.007 | 0.008 | 0.85x |
| 1024x1024 | 0.160 | 0.212 | 0.76x | 0.106 | 0.117 | 0.91x |
| 4096x4096 | 3.045 | 3.791 | 0.80x | 2.234 | 2.187 | **1.02x** |

`codegen.jvm.JvmSimdVectorTemplate` (the copy that ships in a compiled `.class`), one
thread -- within 0.05x of the interpreter twin at every cell, which is the answer to
whether the surrounding 4000-line class changes an inlining decision. It does not:

| shape | Graal ratio | C2 ratio |
| --- | --- | --- |
| 288x288 | 0.83x | 0.87x |
| 1024x1024 | 0.77x | 0.90x |
| 4096x4096 | 0.82x | **1.02x** |

`--parallel`, 20 threads, 4096x4096 -- **the only place bf16 wins today**, because
spreading the rows across cores is what finally lifts the accumulator chain off the
critical path and lets the kernel become bandwidth-bound:

| kernels | Graal f32 | Graal bf16 | Graal ratio | C2 f32 | C2 bf16 | C2 ratio |
| --- | --- | --- | --- | --- | --- | --- |
| `VecSimdKernels` | 0.438 | 0.337 | 1.30x | 0.436 | 0.409 | 1.07x |
| `JvmSimdVectorTemplate` | 0.449 | 0.375 | 1.20x | 0.437 | 0.346 | 1.26x |

An earlier, quieter run of the same code gave 1.37x (Graal) and 1.56x (C2). The spread is
the other lanes; the direction is not in doubt, and it agrees with `.todo/482`'s
`Quant.java par` (1.63x / 1.70x at 20 threads). Peak bf16 throughput seen: 55 Gelem/s
against f32's 38.

The two routes that lose, on both JITs at every shape, exactly as the spike found:

| variant | Graal | C2 |
| --- | --- | --- |
| bf16 widened into an f32 scratch, then the f32 kernel (reuse 1) | 0.64-0.69x | 0.60-0.65x |

And the `.todo/480` shape, 4 accumulators + FMA, **both arms** (probe, not shipped):

| shape | Graal f32 | Graal bf16 | Graal ratio | C2 f32 | C2 bf16 | C2 ratio |
| --- | --- | --- | --- | --- | --- | --- |
| 288x288 | 0.005 | 0.006 | 0.82x | 0.004 | 0.005 | 0.79x |
| 1024x1024 | 0.065 | 0.074 | 0.88x | 0.060 | 0.054 | 1.10x |
| 4096x4096 | 2.030 | 1.274 | **1.59x** | 1.976 | 1.003 | **1.97x** |

Against `.todo/482`'s 1.48x / 2.06x at 4096x4096 and 0.88x at 1024x1024 (round 1, Graal).
The reproduction is exact enough that the difference between this table and the shipped
one is entirely the accumulator count.

Note what the f32 column alone says: 3.045 -> 2.030 ms under Graal, 2.234 -> 1.976 under
C2. **`.todo/480` is worth 1.1-1.5x to the f32 GEMV on its own**, before bf16 exists.

## Still open in `.todo/488`

- The interception wiring (`--simd` / `--parallel` binding these kernels) -- the packed
  bf16 array type does not exist yet, so the kernels take bare `short[]`.
- The element-wise `vec:` kernels over bf16 (widen, compute in f32, narrow on store).
- x64. Every number here is aarch64. A left shift is a left shift, so the shape should
  hold, but the crossover size will move with the cache hierarchy.
- The cache-resident regression (0.76-0.91x at 288x288 and 1024x1024) and whether a size
  threshold is wanted. Do not decide it until `.todo/480` has moved the baseline.
