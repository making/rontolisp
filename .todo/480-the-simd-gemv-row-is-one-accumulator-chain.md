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
