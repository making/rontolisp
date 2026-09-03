# 684. The f64 `--simd` GEMV row is still one accumulator chain -- and unmeasured

Difficulty: Low

Left behind by `.todo/480` (2026-09-03), which gave the **f32** GEMV row four independent
accumulators in all four `--simd` implementations and left `matvecRows`, the f64 twin,
exactly as it was: one `DoubleVector` accumulator, `vacc = vacc.add(w.mul(x))`, in
`eval/VecSimdKernels`, `codegen/jvm/JvmSimdVectorTemplate`, `WasmVecSimdRuntimeBuilder`
and `NoGcWasmCompiler`'s `WasmVecLoops.simdDot` f64 arm.

The f64 row has the same mechanism -- one accumulator is one dependency chain, so the row
runs at roughly one add-latency per lane group however wide the core issues -- so it
plausibly has the same problem. **That is a hypothesis, not a measurement.** It was not
measured, and the f32 numbers do not carry over unexamined:

- f32 gained **1.51x at 4096x4096, 2.40x at 1024x1024 and 1.64x at 288x288 under Graal**
  (1.12x / 1.89x / 1.39x under C2), one thread, on the GB10
  (`.todo/480-the-simd-gemv-row-is-one-accumulator-chain/Acc.java`, 2026-09-03).
- f64 runs `f64x2`: **half the lanes per group**, so each chain step moves half the
  elements for the same latency and the arithmetic intensity per accumulator differs.
  Whether that makes the extra accumulators more valuable (the chain is relatively more
  of the cost) or less (fewer bytes in flight per group) is not derivable from the f32
  numbers. **Measure before deciding.**

## Do

1. Extend `.todo/480-.../Acc.java` -- or a `DoubleVector` sibling of it -- to the f64 row
   at 288x288, 1024x1024 and 4096x4096, 1/2/4/8 accumulators, under BOTH JITs (Graal and
   `-XX:-UseJVMCICompiler`). Keep the harness's structure: dispatch ONCE per GEMV with the
   row loop inside the timed method. `.todo/480`'s first probe dispatched per row through
   a five-implementation interface, went megamorphic, stopped inlining the Vector API and
   measured boxing instead of the fold -- it reported a 0.48x "regression" that did not
   exist.
2. If it pays, land it in all four implementations together with the same gate shape
   (`2 * accumulators * lanes` columns, which is 16 at `f64x2` -- note that collides with
   `MATVEC_ROW_THRESHOLD = 16`, so decide whether the two gates merge there) and re-pin
   the `#d` control rows of the probes in `eval/VecSimdTest`, `eval/LinalgSimdTest`,
   `codegen/jvm/JvmSimdAccelCompilerTest` and
   `codegen/wasm/WasmLispCompilerIntegrationTest`. They currently assert that `#d`
   reductions are EXACT and unmoved (16778239 on both paths) -- an f64 accumulator is
   exact on those inputs whatever the fold order, so they may well not move at all, which
   is worth knowing either way.
3. If it does not pay, say so here and delete the item: a measured "no" is the deliverable.

## Why it is not urgent

`vec:` defaults to `double-float`, but the workload that made `.todo/480` worth doing is
LLM inference, which is `#f` throughout (`examples/llama2`, and every checkpoint in
`.todo/489`'s ladder). No `#d` GEMV in this repo is on a hot path. So this is a real gap
with no known victim -- the reason it is filed rather than done.

`linalg:dot`'s matrix-by-vector case rides the same kernel (`LinalgSimdKernels.matvec`
delegates to `VecSimdKernels.matvec`), so it would move with this, as its f32 sibling did.
