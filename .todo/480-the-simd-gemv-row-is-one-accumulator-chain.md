# 480. The `--simd` GEMV row is one accumulator chain: ~1 MAC per cycle on hot data

Difficulty: Medium

Found 2026-08-22 while closing `.todo/457` (llama2 on the JVM now beats kishida's Java
port on both thread counts; the remaining single-thread cost is the GEMVs themselves).

`matvecRowsF` / `matvecRows` (`JvmSimdVectorTemplate`, mirrored in `eval.VecSimdKernels`,
and the wasm `--simd` kernels' f32x4 chain) reduce a row with ONE vector accumulator:
`vacc = vacc.add(w.mul(x))` -- a dependency chain of 4-cycle latency per 4 MACs, so a
row runs at ~1 MAC/cycle however wide the core's issue is. Measured, JVM class, GB10
(Cortex-X925 3.9 GHz), `.todo/457`'s scratch `g.lisp`:

| product | per call | MAC/s |
|---|---|---|
| `vec:matvec` 288x288 single-float, matrix HOT in L2 | 12.6 us | 6.6 G |
| the same under `--simd --parallel` (20 threads) | 4.0 us | |
| 256x48 (attention scores; lanes since the row threshold moved to 16) | 1.8 us | 6.8 G |

At 16 bytes of weights per chain step and 3.9 GHz / 4 cycles, one chain streams ~15.6
GB/s -- below what one X925 can pull from DRAM/L2, so a single-threaded decode is
latency-bound before it is bandwidth-bound. llama2 stories15M single-thread is ~3.0 ms a
token, ~2.4 ms of it in GEMVs over ~61 MB of weights (20 GB/s); two or four independent
accumulators per row (lane groups, summed at the end) would lift that toward the
bandwidth ceiling, plausibly 1.3-1.5x on the single-thread row (339 tok/s today).

## Why it is not a local change

The single f32x4 chain is the CROSS-BACKEND bit-identity contract of the f32 reductions
(`.kb/vec.md`, "FSPECIES_REDUCE is always f32x4 ... what lets every --simd backend
agree"): the interpreter `--simd`, the JVM class and wasm-GC `--simd` reduce in the same
order today. A multi-chain row changes the bits of every `vec:matvec`/`-into` (and
`vec:dot` if its chain changes too), so it has to land in `VecSimdKernels`,
`JvmSimdVectorTemplate` and `WasmVecSimdRuntimeBuilder` (+ `--no-gc`) TOGETHER, with the
pinning tests re-pinned and the llama2 `equals` stories re-verified on all four backends
(greedy argmax has survived every lane-order change so far, but check). `linalg:dot`'s
M.v kernels (`LinalgSimdKernels`) are a separate decision.

## Acceptance

- `vec:matvec` 288x288 hot under 8 us single-threaded on the GB10, bit-identical across
  the three `--simd` implementations, `vec:dot` either unchanged or changed in all three.
- llama2 `--simd` (one thread) re-measured beside the gist's single-thread row; the
  README table re-measured on one day.

## `.todo/488` needs this as a PREREQUISITE (2026-09-03)

The fused `bfloat16` GEMV kernels landed that day and measure **at parity with f32 on one
thread** (0.80x Graal / 1.02x C2 at 4096x4096), against the 1.60x `.todo/482`'s spike
promised. The cause is exactly the chain this item is about, seen from the other side: the
row is latency-bound at 5.5-7.6 Gelem/s, well short of the memory wall, so halving the
weight bytes has no bandwidth to save. Give BOTH arms four accumulators + FMA and the
spike reproduces almost exactly -- 1.59x Graal / 1.97x C2, against its 1.48x / 2.06x
(`.todo/488-the-fused-bfloat16-gemv-kernels/README.md`).

And `.todo/488` cannot route around it: its safety contract is *fused ==
widen-then-f32-kernel, bit for bit*, so the bf16 arm must carry whatever accumulator count
the f32 arm carries. bf16 gains accumulators when and only when this item lands.

Two things that follow:

- **This item is worth 1.1-1.5x to the f32 GEMV on its own**, measured on the same run:
  4096x4096 one thread, 3.045 -> 2.030 ms under Graal and 2.234 -> 1.976 ms under C2, in a
  four-accumulator probe of the same shape. That is independent of bf16 and consistent
  with the 1.3-1.5x estimated above.
- Whatever accumulator count lands here must land in the bf16 kernels of both files in the
  same commit, or `VecSimdBf16KernelsTest` / `JvmSimdVectorTemplateBf16Test` go red -- by
  design: they are the alarm that the two arms have drifted apart.

## Built, 2026-09-03

Four independent accumulators per f32 GEMV row above a 32-column gate, in all four
`--simd` implementations at once. Numbers, harness and the reasoning behind both constants:
`.todo/480-the-simd-gemv-row-is-one-accumulator-chain/README.md`.

- `eval/VecSimdKernels.matvecRowsF` (interpreter)
- `codegen/jvm/JvmSimdVectorTemplate.matvecRowsF` (the embedded bridge a `.class` ships)
- `codegen/wasm/WasmVecSimdRuntimeBuilder.emitRowDotAcc` (wasm-GC)
- `codegen/wasm/WasmVecLoops.simdMatvecRowDotF32`, called from
  `NoGcWasmCompiler.compileSimdMatvec` (`--no-gc`)

The order, identical in all four: `(a0 + a1) + (a2 + a3)` into the single accumulator that
then takes the leftover whole lane groups and the scalar tail in index order. Constants:
`MATVEC_ACCUMULATORS = 4`, `MATVEC_ACC_THRESHOLD = 2 * MATVEC_ACCUMULATORS * lanes = 32`.

### The decisions, and why

- **No fused multiply-add**, which is what made the item possible at all. wasm SIMD has no
  deterministic FMA (`relaxed_madd` is explicitly allowed to differ between engines, so it
  can never carry a bit-identity contract), and measurement said the win does not need one:
  4-acc mul-then-add is level with 4-acc FMA (1024x1024, Graal, 2.40x against 2.45x).
- **`vec:dot` and `vec:sum` are unchanged**, the acceptance criterion's second option.
  Changing `vec:dot` would move `vec:norm`, `vec:mean` and every `linalg` user transitively
  -- a blast radius well beyond the GEMV -- and `linalg:dot`'s own M.v kernels were declared
  out of scope, so leaving `vec:dot` alone keeps the two consistent.
  **Consequence, deliberate and observable:** `(vec:matvec W x)[i]` no longer equals
  `(vec:dot row_i x)`. Same value mathematically, different summation order, so possibly
  different last bits. `doc/{en,ja}/guides/simd-acceleration.md` and `.kb/vec.md` now say
  so; nothing may assume they agree.
- **`linalg:dot`'s matrix-by-vector case moved with it, unavoidably.** It is not a kernel
  of its own: `LinalgSimdKernels.matvecF` delegates to `VecSimdKernels.matvecF`,
  `JvmSimdVectorTemplate` reaches the same `matvecRowsF`, and both wasm builders route it
  "via the vec: matvec kernel". Forking to preserve the old bits would duplicate code to
  protect a number nothing promised and leave `linalg:dot`'s M.v slower than `vec:matvec`
  for no reason. The matrix-MATRIX product (`matmulRowsF`, a kernel of its own) and
  `linalg:dot` of two vectors are untouched.
- **f64 is untouched and unmeasured** -- `.todo/684`.

### Re-pinned

The f32 single-precision probe moves from `2^24 + 768 = 16777984` to `2^24 + 960 =
16778176` wherever it goes through a GEMV (1024 columns groups as sixteen lanes rather than
four): `eval/VecSimdTest`, `eval/LinalgSimdTest`, `codegen/jvm/JvmSimdAccelCompilerTest`
and two probes in `codegen/wasm/WasmLispCompilerIntegrationTest`. `vec:dot` / `vec:sum` /
`linalg:sum` / `linalg:mean` rows keep 16777984; every `#d` row is unchanged. `.kb/vec.md`,
`.kb/linalg-simd.md` and `.kb/gpu.md` carry the new value. `ci-spec.yaml` needs nothing:
`CiSpecE2eTest` runs without `--simd`, so it is the oracle rather than a subject.

### Verified

**The gate fires at the same column count, in the same direction, on all four `--simd`
implementations.** The 2^24 probe at 16 columns (one chain) and 32 (four chains) answers
**16777228** and **16777246** on the interpreter, a compiled `.class`, wasm-GC and
`--no-gc` alike -- both counts are multiples of the lane count, so nobody folds a partial
group and the four must agree exactly. 31 columns still answers with the single chain
(16777240) on the interpreter and the JVM, which pins the comparison as `>=` and not `>`.
Pinned in `eval/VecSimdTest`, `codegen/jvm/JvmSimdAccelCompilerTest` and
`codegen/wasm/WasmLispCompilerIntegrationTest` (both wasm lowerings).

That last one closed a hole this item opened: every existing `--no-gc` GEMV case ran at 5
or 6 columns, far below the gate, so `WasmVecLoops.simdMatvecRowDotF32` -- a hand-written
multi-accumulator wasm loop -- was emitted by the compiler and executed by nothing.

**Acceptance, `vec:matvec` 288x288 hot, one thread** (the shipped kernel through
`eval/Bf16GemvBench`, same harness before and after, GB10, quiet box):

| | before (1 chain) | after (4 chains) |
| --- | --- | --- |
| Graal | 10 us | **6 us** |
| C2 | 7 us | **4 us** |

Under the 8 us the item asked for, on both JITs.

**llama2 stories15M, 60 greedy tokens, `Once upon a time`: byte-identical on eight legs** --
interpreter (`--simd`, `--simd --parallel`, and **the scalar path with no flag at all**),
a compiled `.class` (`--simd`, `--simd --parallel`, and the same class on a JVM WITHOUT
the incubator module, which degrades to the scalar reference), wasm-GC `--simd` and the
wasm component `--simd`. Same md5, 222 bytes, and the text `examples/examples.yaml`
already expected. **The scalar legs agreeing is the stronger half of the claim**: the four
`--simd` implementations match each other, and they also match the path that does no lane
folding at all, because greedy argmax absorbs a last-bit difference in the logits.
`ExamplesE2eTest`'s llama2 slice is green (19 legs).

Throughput on the same box, 256 tokens, JVM class: `--simd` **359 tok/s** on one thread,
`--simd --parallel` **607** on twenty (386 pinned to one thread). The `--simd` figure is
the one to compare across `.todo/480`; it was 336 when `.todo/457` closed (2026-08-22, a
222-token story, so not exactly the same run).

### The cols=48 probe disagrees with the model, and the model wins (2026-09-03)

Recorded because the probe's sign is the wrong thing to trust, and this item nearly shipped
a threshold chosen from one.

x86-64 (`dorian`, Xeon E5-2697A v4, Broadwell, AVX2, GraalVM 25.0.4) reports a LOSS at 48
columns when `Gate.java`'s `one(256, 48)` is run alone in a fresh JVM, ten times, on each
JIT: Graal **0.88-0.97x**, C2 **0.74-0.93x**. Neither spread contains 1.0. Measured on the
MODEL instead -- `llama2.lisp` built from `aad63afd` (this item's parent) and from
`6a1c47e3` (this item), both compiled to a `.class` with `--simd`, run over stories15M,
whose `dim=288 heads=6` makes its attention head dimension exactly 48 -- the sign reverses:

> stories15M, JVM class output, one thread, a quiet dorian (load 0.6), twelve alternating
> pairs: **tok/s 111.96 -> 118.91, 1.062x.** All twelve quantile ratios are at or above
> 1.000 (1.003-1.073). The 256-token outputs of the two builds are byte-identical under
> `cmp`. **`Gate.java`'s solo `one(256,48)` reports 0.88-0.97x (Graal) / 0.74-0.93x (C2) on
> the same box, and the model's sign is the opposite** -- most of llama2's GEMVs have 288 or
> 768 columns, far above the gate, and their win outweighs the two attention GEMVs that sit
> near it.

**The sign of a solo probe at cols=48 does not predict the sign for the model**, and AVX2
does not break the bit-identity contract either.

An earlier, louder run of the same pairs on a loaded box (load up to 288) reported
1.08-1.24x and a 1.088x median. That figure is withdrawn: **load inflates the ratio**, not
just its variance -- the slower build loses more to contention than the faster one, so the
quiet 1.062x is the number to carry. See `.kb/measurement-probes.md`, Rule 5, observation E.

### The harness itself was wrong twice, in two different ways

`Gate.java` returns THREE ratios for the same shape (rows 256, cols 48) inside one process
on the GB10 -- 0.92x in its section A, 1.29x in section B, 1.21x in its closing table -- and
0.93x / 1.24x on dorian, where the spread crosses 1.0 and changes the conclusion's sign. It
reproduces on a quiet box and a loaded one, so it is not noise: both kernels share one
generic timing method with the baseline always first, so they share a compilation and a
profile. `Solo.java` removes that (one timing method per kernel, called by name, one shape
and optionally one KERNEL per process); its GB10 numbers are still to be taken.

**The README's head-dimension table carried 1.21x and its column sweep 1.29x for the same
shape, in adjacent tables, without noticing they were the same shape.** Both are warm-side
numbers. Whatever `Solo.java` says, the model measurement above is what decides this item.

The general form of all of this now lives in `.kb/measurement-probes.md`, Rule 5.

### What this is NOT verified on

- **x64.** Every number is aarch64 (GB10). The gate is derived from the lane count and the
  accumulator count rather than fitted to a crossover, so it should carry; `.todo/482`'s
  x64 host runs the same f32 GEMV 2.6-2.9x slower in absolute terms without changing the
  shape of anything. Untested all the same.
  **Bit-identity is not at risk either way**: `FSPECIES_REDUCE` stays `SPECIES_128`, so the
  fold order is the same on an AVX-512 host as on NEON. The threshold is a PERFORMANCE
  number and machine-dependent; the lane pin is a CORRECTNESS one and is not.
- **The path below the gate, end to end.** Dropping the gate from the drafted 96 to 32 put
  stories15M's 48-column attention GEMV ABOVE it, so `ExamplesE2eTest` now exercises the
  new four-accumulator row for real -- which it would not have at 96. The untested region
  moved rather than closed: **columns 16-31 take the single-chain path and no example
  reaches them**, except transiently as an attention `V^T . att`'s sequence length grows
  through them, which no test asserts on directly.
- **Models with a head dimension that is not 48.** stories15M is the only checkpoint that
  runs today. The gate is a pure function of the column count, so 64/128/256 take the same
  code path as 48, but no end-to-end run has proved it until `.todo/489`'s ladder does.
