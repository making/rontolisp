# 98 — an example that actually shows the `--simd` win

**Motivation:** `examples/ml/nn-vec.lisp` (todo-95 Part 2) demonstrates the `vec:` / `linalg:`
API and `vec:matvec` (GEMV) cleanly, but its tensors are TINY — rows of length 2 and 4, far
below `JvmSimdVectorTemplate.THRESHOLD = 128`, so every kernel takes the scalar-tail path and
`--simd` does literally nothing measurable. We have a real SIMD acceleration layer (JVM
`jdk.incubator.vector` for `vec:add`/`sub`/`mul`/`scale`/`dot`/`sum`/`matvec`, both `#d` and
`#f`; `--no-gc` native `v128` `f64x2`/`f32x4`) with ZERO example that makes it pay off. Add one
where `--simd` gives a visible, measured speedup.

## What to build (pick one; a benchmark is the smallest, a transformer the most compelling)

1. **A GEMV / dot-product timing benchmark** (smallest, self-contained): build a large matrix
   (e.g. `2048 x 2048` `#d` or `#f`) and vector, run `(vec:matvec W x)` in a timed loop
   (`get-internal-real-time` / `get-universal-time` — check what's available cross-backend),
   print elapsed + a checksum (`vec:sum` of the result) so correctness is verifiable. The
   speedup is seen by running the SAME file with and without `--simd`:
   `rontolisp bench.lisp -o Bench.class --simd` vs plain, timed. Document the two commands +
   expected order-of-magnitude. `n >= 128` rows guarantee the FloatVector loop runs.
2. **A stories15M-scale llama2 single-token decode** (the original todo-95 Part 2 payoff, à la
   kishida Llama.java): every projection / FFN / classifier is a `vec:matvec`; f32 throughout
   once todo-97 (single-float linalg) lands, or f64 now. Needs a weights loader (the karpathy
   `stories15M.bin` format — a header + flat f32 blobs; `read-byte` on a file stream exists on
   all backends) + tokenizer. BIG: this is a multi-file example, its own sub-project; the
   memory pins it as the "compelling single-float example". Real 1.1B model as f32 = 4.4GB, so
   stay at stories15M (~60MB f32) / 110M scale.
3. **A middle option**: a small MLP on a REAL dataset (e.g. a digits/MNIST-subset classifier)
   with hidden layers wide enough (>= 128) that `vec:matvec` hits the vector loop — more
   convincing than XOR, less work than llama2. `examples/ml/deep-digits.lisp` /
   `mlp.lisp` are precedents (fixed-seed LCG for determinism).

**Recommendation:** start with (1) the GEMV benchmark — it directly proves the `--simd` layer
works at scale and is a clean, deterministic, single-file `examples/` addition; then consider
(2) llama2 as a follow-up once todo-97 gives true f32 linalg.

## Constraints / notes

- **THRESHOLD = 128**: the FloatVector/DoubleVector loop only runs for `n >= 128` per row/vec
  (`JvmSimdVectorTemplate`). The example's inner dimension MUST be >= 128 (ideally >> for a
  visible win) or `--simd` is a no-op.
- **Determinism vs timing**: a timing number is inherently non-repeatable, so the
  `examples.yaml` entry should `contains:`-check a fixed header + a deterministic CHECKSUM line
  (not the elapsed time) — mirror the `mlp.lisp` / `maze-rl.lisp` random-output handling. Use
  integer/power-of-two inputs so the checksum is f64/f32-exact and byte-identical cross-backend
  (respecting the WASM float-print divergence: a `vec:sum`/`vec:dot` SCALAR checksum is safe;
  never print a large `#f` element-wise result).
- **`--simd` is JVM-only** at the example level (the `--no-gc` v128 layer is separate and needs
  a host `--invoke`, not a `run`); the benchmark's headline is the JVM `--simd`-vs-scalar
  comparison. WASM P1/component run the scalar reference (still correct, just not the SIMD
  showcase).
- Register in `examples/examples.yaml` (`[interpreter, jvm, wasm]`, `contains` the header +
  checksum). If it's llama2-scale, it may be too heavy for the E2E harness — then keep it out
  of `examples.yaml` (or mark it doc-only) and document how to run it.

## Pointers

- The accel layer: `.kb/vec.md` (acceleration layers 1 & 2), `JvmSimdVectorTemplate`
  (THRESHOLD, the kernels incl. `simdMatvec`), `doc/{en,ja}/guides/simd-acceleration.md`.
- `vec:matvec` (GEMV) landed in todo-95 Part 2 (2026-07-08).
- Example precedents: `examples/ml/nn-vec.lisp` (small), `mlp.lisp` / `deep-digits.lisp`
  (deterministic ML), the `examples.yaml` `contains`/`skip` idioms.
- llama2 refs: kishida Llama.java (f32, FloatVector, GEMV), karpathy llama2.c
  (`matmul(xout,x,w,n,d)` = GEMV, `stories15M.bin` format).
- True f32 end-to-end needs **todo-97** (single-float linalg output); f64 works today.
