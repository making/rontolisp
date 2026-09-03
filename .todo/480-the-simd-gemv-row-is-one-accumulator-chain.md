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
