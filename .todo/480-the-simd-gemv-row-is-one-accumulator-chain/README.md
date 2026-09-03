# The accumulator-count probes for `.todo/480`

Throwaway single-file source-launcher programs, kept so the numbers in
`../480-the-simd-gemv-row-is-one-accumulator-chain.md` can be re-derived. Outside `src/`,
not in the reactor, not formatted, nothing builds or tests them.

```bash
java --add-modules jdk.incubator.vector <File>.java                     # Graal
java -XX:-UseJVMCICompiler --add-modules jdk.incubator.vector <File>.java   # C2
```

| file | question |
| --- | --- |
| `Acc.java` | 1 / 2 / 4 / 8 accumulators, mul-then-add, plus a 4-accumulator FMA arm, at 288x288 / 256x48 / 1024x1024 / 4096x4096. **Its small-shape rows are not usable -- see below.** |
| `Sweep.java` | a first column sweep at fixed total work; superseded by `Gate.java` |
| `Gate.java` | **the one the decisions came from**: the gate, at the column and row counts real models use |

## The machine these numbers came from

NVIDIA GB10 (Grace Blackwell), aarch64 Cortex-X925, 20 cores, Oracle GraalVM 25.0.4, one
thread, box otherwise idle. A different machine changes every absolute number; what should
survive is the shape, and the gate, which is derived from the kernel rather than measured.

## The harness lesson, which changed the answer

`Acc.java` dispatches the row kernel through a five-implementation interface **once per
row**. That call site is megamorphic, so the JIT does not inline it, so the Vector API is
not intrinsified, so the cost scales with the number of live vectors -- it measures BOXING,
not the fold. At 256x48 under C2 it reports 2-acc 0.45x, 4-acc 0.48x, 8-acc 0.30x and
4-acc-with-FMA 0.51x: every multi-accumulator variant halved, FMA included. FMA cannot be
slower than the mul-then-add it replaces, so the harness, not the kernel, was wrong. This
is `.todo/482` round 2's C2 inlining cliff, reproduced by accident in a probe.

`Gate.java` dispatches **once per GEMV** with the row loop inside the timed method -- the
shipped kernel's own structure -- and has two implementations, so the site stays bimorphic
and inlines. Its numbers are the ones to trust. `Acc.java`'s LARGE shapes are still good
(the per-row dispatch amortizes over many columns) and are where the accumulator count was
decided.

**A 0.48x regression at 48 columns was reported from `Acc.java` and does not exist.** It
is why the gate was first drafted at 96 columns; the clean measurement moved it to 32.

## What was decided

**Four accumulators** (`Acc.java`, large shapes, 4-acc vs the shipped 1-acc row):

| shape | 2 acc | **4 acc** | 8 acc | 4 acc + FMA |
| --- | --- | --- | --- | --- |
| 288x288 Graal / C2 | 1.30x / 1.27x | **1.64x / 1.39x** | 1.41x / 1.06x | 1.66x / 1.51x |
| 1024x1024 Graal / C2 | 1.49x / 1.71x | **2.40x / 1.89x** | 2.16x / 1.55x | 2.45x / 1.90x |
| 4096x4096 Graal / C2 | 1.44x / 1.18x | **1.51x / 1.12x** | 1.37x / 1.11x | 1.40x / 1.15x |

Eight lose to four at every shape; two lose to four everywhere but 4096x4096 under C2,
where the kernel is DRAM-bound and every count converges.

**No FMA.** It is level with mul-then-add (1024x1024: 2.45x against 2.40x), and wasm SIMD
has no deterministic fused multiply-add -- `relaxed_madd` may differ between engines, so it
cannot carry a bit-identity contract. The win not needing it is what made the item
possible.

**The gate is 32 columns** = `2 * MATVEC_ACCUMULATORS * lanes`, two full wide iterations.

`Gate.java`, 4-acc vs 1-acc at the head dimensions real models use (rows = 256):

| columns | model | Graal | C2 |
| --- | --- | --- | --- |
| 48 | stories15M | 1.21x | 1.15x |
| 64 | SmolLM2-135M, TinyLlama-1.1B, LFM2.5-1.2B | 1.23x | 1.26x |
| 96 | (the rejected threshold) | 1.26x | 1.43x |
| 128 | Qwen3-0.6B; the Gated DeltaNet product | 1.27x | 1.52x |
| 256 | Qwen3.5-0.8B | 1.29x | 1.57x |

Four accumulators win at **every** real head dimension, so the gate is not a
head-dimension question and a threshold of 96 would have cost four of the five models a
1.15-1.26x win. What a gate is genuinely for is a row too short to fill the wide loop
twice -- `Gate.java` table B, columns swept with the rows fixed:

| columns | 16 | 24 | 32 | 48 | 64 | 96 | 128 | 256 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Graal | 1.15x | 1.09x | 1.25x | 1.29x | 1.24x | 1.26x | 1.27x | 1.29x |
| C2 | 0.88x | **0.70x** | 1.02x | 1.15x | 1.25x | 1.43x | 1.40x | 1.78x |

24 columns is one wide iteration plus two leftover lane groups: the setup is most of the
row. 32 recovers. So the gate is the kernel's own shape and not a fitted number, it sits
below every real head dimension, and it sits above `MATVEC_ROW_THRESHOLD = 16` so a row
between the two runs the single chain it always ran.

**The row count is deliberately not consulted.** Four accumulators lose only at one or two
rows (`Gate.java` table A, 1 row: 0.48x Graal / 0.42x C2 at 48 columns) -- the first token
of a generation. Gating on the row count would predict better and is forbidden: an
attention `V^T . att` is a GEMV whose COLUMNS are the sequence length, so one call site
crosses the gate during a run, and the gate may depend on the column count and nothing
else or the four `--simd` implementations stop agreeing bit for bit. Correctness over
optimality, on purpose.
