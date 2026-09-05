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

**The cliff did not reproduce here**, on 2026-09-03 or on 2026-09-05: the
one-small-method-per-width rule held at every shape on both JITs. (The ratios quoted in
this paragraph on 2026-09-03 -- 0.85-1.02x under C2, 0.76-0.82x under Graal -- were
against the one-accumulator f32 baseline of the day; the current ones are at the end of
this file.)

## The finding of 2026-09-03: the 1.6x is `.todo/480`'s, not this item's

**Superseded by the 2026-09-05 measurement at the end of this file, which is the record
to read. `.todo/480` landed, and the headline reproduces.** Kept because it is the
reasoning that predicted it, and because every table between here and there was taken
against a one-accumulator f32 baseline that no longer exists.

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

## Provisional numbers (2026-09-03) -- SUPERSEDED, see the end of this file

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

## The measurement that closed the item, 2026-09-05

Taken on a box cleared for it: no maven anywhere, the other lanes held off the JVM.
**Base commit `2275c000`. NVIDIA GB10, aarch64 Cortex-X925, NEON 128-bit, 20 cores,
Oracle GraalVM 25.0.4. `RONTOLISP_THREADS` UNSET, so `SimdParallel.threads()` took
`Runtime.availableProcessors()` = **20 threads, the calling thread included**. Load
average 0.64 immediately before the first JVM and 0.67 immediately after the last.**

**Every ratio in this section is GB10-local and self-contained**: the f32 arm and the
bf16 arm of a cell are timed in the SAME JVM, in the same run, over the same values, and
the harness divides one by the other. No number here divides by an f32 figure from
`.todo/482`, `.todo/670` or `dorian`, so a contaminated baseline elsewhere cannot flatter
a bf16 result here. The 0.80x / 1.02x that this section supersedes were GB10 figures from
this same harness on 2026-09-03, taken beside two busy lanes -- not dorian's, and not
`Worth.java`'s. `dorian`, the project's other
box, is Broadwell AVX2 with 64 threads: a ratio measured here is NOT a ratio measured
there, and no table in this file covers both.

f32 activations throughout; the bf16 weights are narrowed from the same N(0, 0.02)
gaussians the f32 baseline runs over, so both arms multiply the same values (the
checksum line in the harness output asserts the two answers are bit-identical, and it
printed `identical=true` at every shape on both JITs).

### The shipped kernels, one thread -- bf16 fused against the shipped f32 GEMV

`eval.VecSimdKernels` (the interpreter's):

| shape | Graal f32 | Graal bf16 | Graal | C2 f32 | C2 bf16 | C2 |
| --- | --- | --- | --- | --- | --- | --- |
| 288x288 | 0.006 ms | 0.008 | 0.73x | 0.004 | 0.005 | 0.75x |
| 1024x1024 | 0.063 | 0.088 | 0.72x | 0.048 | 0.062 | 0.77x |
| 4096x4096 | 2.172 | 1.458 | **1.49x** | 2.140 | 1.069 | **2.00x** |

`codegen.jvm.JvmSimdVectorTemplate` (the copy embedded in every `--simd` `.class`, and
now reached through the real bridge entries over headered arrays, so the header
arithmetic is timed too):

| shape | Graal f32 | Graal bf16 | Graal | C2 f32 | C2 bf16 | C2 |
| --- | --- | --- | --- | --- | --- | --- |
| 288x288 | 0.006 ms | 0.008 | 0.76x | 0.005 | 0.006 | 0.83x |
| 1024x1024 | 0.064 | 0.090 | 0.72x | 0.055 | 0.066 | 0.84x |
| 4096x4096 | 1.982 | 1.503 | **1.32x** | 2.026 | 1.118 | **1.81x** |

The two harnesses agree to within 0.17x, so the surrounding 4000-line class still is not
changing an inlining decision -- the question the template harness exists to answer.

### What superseded what, and why the number moved

**The item's headline 1.60x now reproduces against the shipped kernels (1.32-2.00x at
4096x4096), and the 2026-09-03 tables above -- 0.80x Graal / 1.02x C2 -- are withdrawn.**
Nothing about the bf16 arm changed to do it. What changed is the BASELINE: `.todo/480`
landed four independent accumulators in the GEMV row, in all four `--simd`
implementations at once, and the bf16 arm inherits them because the equivalence contract
forces it to carry the f32 arm's accumulator count.

The mechanism is visible in the same run, in the `f32 4acc+fma` probe row. At 4096x4096
the f32 arm is **unchanged** by four accumulators -- 2.184 ms against the shipped
kernel's 2.172 under Graal, 2.102 against 2.140 under C2 -- because at 67 MB it was
already bandwidth-bound and had no dependency chain left to hide. The bf16 arm was not:
halving the weight bytes buys nothing while a single accumulator chain bounds the row, so
before `.todo/480` the fused kernel spent its bandwidth saving on latency it could not
use. Four accumulators put the bf16 row on the same bound the f32 row was already on, and
the halved bytes finally show up as speed. That is the whole story of the 0.80x -> 1.49x
move, and it is why "the 1.6x is `.todo/480`'s, not this item's" was the right diagnosis
on 2026-09-03.

The `4acc+fma` probe -- the `.todo/482` shape, which the shipped kernels now differ from
only by the second rounding (never `fma`: wasm SIMD has no deterministic fused
multiply-add, so a kernel needing one could not be mirrored) -- reaches 1.74x Graal /
2.14x C2 at 4096x4096. The gap to the shipped 1.49x / 2.00x is what that second rounding
costs, and it is the price of the cross-backend contract, not a regression.

C2's 0.20x inlining cliff did not reproduce at any shape. The one-small-method-per-width
rule held.

### The route that loses, still

| variant, 4096x4096 | Graal | C2 |
| --- | --- | --- |
| bf16 widened into an f32 scratch, then the f32 kernel | 0.57x | 0.56x |

0.46-0.57x at every shape on both JITs. This matters beyond curiosity: it is the ONLY
alternative to the fused kernel that is bit-identical to it, and it loses everywhere, so
there is nothing for a size gate to switch to (below).

### `--parallel`, 20 threads (`RONTOLISP_THREADS` unset -> `availableProcessors()` = 20)

| kernels, shape | Graal f32 | Graal bf16 | Graal | C2 f32 | C2 bf16 | C2 |
| --- | --- | --- | --- | --- | --- | --- |
| eval, 1024x1024 | 0.027 ms | 0.021 | 1.26x | 0.025 | 0.017 | 1.47x |
| eval, 4096x4096 | 0.413 | 0.289 | 1.43x | 0.408 | 0.317 | 1.29x |
| template, 1024x1024 | 0.025 | 0.018 | 1.37x | 0.025 | 0.017 | 1.47x |
| template, 4096x4096 | 0.409 | 0.393 | 1.04x | 0.394 | 0.398 | 0.99x |

The parallel f32 arm sits at 41-42 Gelem/s in every single cell -- 164 GB/s of weights,
which is this box's ceiling and not a kernel property. The bf16 arm reaches 58 Gelem/s
where it is given the chance and 42 where it is not, and the 4096x4096 template row
(1.04x / 0.99x) is the one cell that disagrees with its eval twin (1.43x / 1.29x) on
identical arithmetic. **Treat the parallel column as the noisy one**: the 2026-09-03 run
recorded the same spread and attributed it to the other lanes, and this run had none, so
the cause is the box's own scheduling and not contention. One run per cell is not enough
here; the single-threaded columns are the ones to quote.

**Qualification added 2026-09-05, after the numbers above were taken.** "This box's
ceiling and not a kernel property" generalises further than this data supports, for two
independent reasons, and the sentence should be read as *a rate the f32 arm reaches on
the shapes measured here* until someone widens it.

- **The same 41-42 Gelem/s appears at BOTH shapes** -- 1024x1024 is 4.2 MB of f32 weights
  and 4096x4096 is 67 MB, and the arm runs 39-42 Gelem/s at each. A GEMV re-reads no
  weight, so both stream; but 4.2 MB can live in this part's system-level cache and 67 MB
  cannot, and a DRAM bandwidth ceiling has no reason to bind the two identically. Either
  the small shape is not cache-resident in the way the single-threaded 0.72-0.84x
  regression implies it is, or 41-42 Gelem/s is a limit of the parallel machinery -- work
  distribution, barrier cost, per-row dispatch -- rather than of memory. Those predict
  different things about every future width, so the question is worth one experiment: run
  the parallel f32 arm at a shape small enough to be unambiguously resident (256x256, say)
  and see whether it still lands on 41-42.
- **On `dorian` the analogous figure is per-MODEL, not per-box.** Measured on a quiet
  machine 2026-09-05 at 32 threads: TinyLlama-1.1B 39 GB/s, Qwen3.5-0.8B 29, Qwen3-0.6B
  22 -- and the ordering was predicted from access shape (plain-llama's big matvecs above
  Gated DeltaNet's 576 small 128x128 GEMVs per token) before those numbers were taken.
  `.todo/670`'s "two independent models on one ceiling" is being corrected on the strength
  of it. That is a different box and a whole-model measurement rather than a kernel one,
  so it does not transfer -- but it removes the prior that a parallel GEMV rate measured
  on one workload is a property of the machine.

Neither point changes any ratio in the tables above; both are within-run and unaffected.
What is affected is the one sentence that stepped from "the f32 arm sits at 41-42" to
"which is this box's ceiling".

### The threshold decision: NO size gate, and why

The cache-resident regression is real and reproduces: **0.72-0.84x on one thread at
1024x1024 (4.2 MB of f32 weights) and 0.73-0.83x at 288x288**, crossing 1.0x somewhere
between 4 MB and 67 MB on this cache hierarchy. It is left in place unconditionally.

- **There is nothing to gate TO.** The only bit-identical alternative is the
  widen-into-scratch route, and it is slower than the fused kernel at every shape on both
  JITs (0.46-0.57x). A gate would switch from the slower-than-f32 path to the
  even-slower path.
- **The other candidate changes the ANSWER.** Declining to the scalar defun above (or
  below) a size makes a reduction's bits depend on the matrix size -- the defun folds in
  f64, the kernel in f32 -- and nothing else in `vec:` has a size gate that moves a
  value across backends. `.kb/vec.md` already forbids consulting anything but the column
  count in the GEMV gate for exactly this reason.
- **The regime that motivates the width is the one where it wins.** An LLM's weights do
  not fit in cache; that is the whole premise of `.todo/489`. And a program that chose
  `#bf16` chose half the memory, which it gets at every shape.
- Under `--parallel` the arm is at or above parity from 1024x1024 up.

Recorded so the next reader does not rediscover it: **fused bf16 stops winning below
roughly 4 MB of weights on a GB10, and the loss there is 0.72-0.84x.**

## Still open, filed as `.todo/696`

- The element-wise `vec:` kernels over a narrow width, and the measurement that has to
  come first (widening vectorizes; round-to-nearest-even narrowing does not).
- Whether the bridge could take a NARROW x NARROW pairing. Short answer, argued there:
  yes, as an extension rather than a rewrite.
- x64. Every number in this file is aarch64.
- The width has no `doc/` page at all, so the fused kernels' user-visible behaviour has
  none either.
