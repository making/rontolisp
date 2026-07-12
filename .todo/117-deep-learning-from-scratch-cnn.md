# Deep Learning from Scratch: ch07-ch08 (CNN) follow-ups

The core ch07/ch08 port landed 2026-07-12 (this todo's original scope):

- linalg additions: `linalg:transpose` optional axes list (rank-n
  permutation, odometer walk; the 2-arg call declines every `--simd`
  transpose kernel by the existing arity guards), public `linalg:pad`
  (constant-0, per-axis `(before after)` pairs or one integer), and the
  internal rank-4 `linalg::%la-im2col`/`%la-col2im` pair (option (a) of
  the original analysis: direct index arithmetic, no pad copy / 6-D
  scratch / transpose materialized; width-preserving). Unit tests in all
  three suites + the `linalg-transpose-axes-pad-im2col-cross-backend`
  ci-spec case; doc pages en/ja incl. the curated functions.md rows.
- `common/util.lisp` (book-shaped `im2col`/`col2im` wrappers),
  `convolution` + `pooling` CLOS layers in `common/layers.lisp` (pooling
  backward = one-hot x dout-column instead of the book's fancy-index
  scatter -- no `linalg:scatter!` was needed),
  `ch07/simple-convnet.lisp` + `gradient-check.lisp` (synthetic 10x10,
  filter-num 3, weight-init-std 0.1 -- 0.01-scale activations pollute the
  NUMERICAL side via ReLU-kink/argmax flips at h = 1e-4) +
  `train-convnet.lisp`, `ch08/deep-convnet.lisp` + `train-deepnet.lisp`.
  examples.yaml: gradient-check = RUN x3 with .expected; train scripts =
  jvm-compile + wasm-component like the other MNIST readers.
- Verified: gradient-check byte-identical on interpreter/--simd/JVM/WASM
  P1/component; train-convnet output identical on interpreter (292s),
  --simd (88s), JVM (2.2s), WASM P1 run (35s).

## Remaining

- **im2col `--simd` interception**: DONE 2026-07-13. `%la-im2col` (arity
  5) / `%la-col2im` (arity 6) are intercepted on all three `--simd`
  backends (`eval.LinalgSimd` + `LinalgSimdKernels`, `JvmLinalgSimdCompiler`
  -> `JvmSimdVectorTemplate.laIm2col/laCol2im`, `WasmLinalgSimdCompiler`
  -> `WasmLinalgSimdRuntimeBuilder` IM2COL/COL2IM; the intercepted set is
  34 now, wasm `userFuncBase()` shifts by 89) -- the first INTERNAL
  (double-colon) members intercepted, see `.kb/linalg-simd.md`. The
  kernel itself is ~400x on the interpreter (20 im2col of a (10 1 28 28)
  batch with a 5x5 filter: 3635 ms -> 9 ms), byte-identical everywhere
  (gradient-check verified on interpreter/`--simd`/JVM/JVM `--simd`/WASM
  P1 scalar+`--simd`/component `--simd`; train-convnet output identical).
  BUT the original "~97% of the accelerated run is the unfold" estimate
  was WRONG: ch07 train-convnet under the jar interpreter `--simd` went
  88.5s -> 67.0s, so im2col was only ~24% of it. The rest is the item
  below.
- **The residual interpreter `--simd` bottleneck = declined shapes of
  already-intercepted members** (measured 2026-07-13 at the SimpleConvNet
  shapes, per call): `(linalg:transpose x '(0 3 1 2))` on (10 30 24 24)
  = 375 ms (the 2-arg axes form declines every transpose kernel by the
  arity guards), `(linalg:mul one-hot flat)` on (43200 4) x (43200 1)
  = 515 ms (a broadcast pair, declined by dims-equal), `(linalg:argmax
  col 1)` / `(linalg:amax col 1)` on (43200 4) = ~150 ms each (axis
  calls route to the variadic defun). Every conv/pool forward AND
  backward pays several of these, which is where the remaining ~65s
  goes -- `linalg:one-hot` itself is cheap (17 ms). Closing the gap
  means kernels for (a) the rank-n axes-permutation transpose, (b) the
  matrix-row/column broadcast shapes of the elementwise ops (the decline
  the `.kb/linalg-simd.md` broadcast note already flags as an
  optimization opportunity), and (c) the axis-1 reductions -- each a
  deliberate extension of the intercepted-call-shape surface across all
  three backends, so it deserves its own todo/design pass.
- **The pretrained-params scripts**: ch07 `apply_filter.py`/
  `visualize_filter.py`, ch08 `misclassified_mnist.py` +
  `half_float_network.py` need `params.pkl`/`deep_convnet_params.pkl`
  re-exported through `tools/export-sample-weight.py`'s RLW1 format (it
  already handles n-dim arrays; add a key-order argument, W1 b1 W2 b2 ...)
  plus a `load-params` into the net classes; the plots would become ASCII
  like ch03 mnist-show. The half-float chapter maps naturally onto packed
  `#f` (`'single-float` constructors) -- linalg is already
  width-polymorphic end to end.

## Deferred items parked here from the ch02-ch06 work

- **ExamplesE2eTest `workFiles:` manifest field**: copy listed data files
  into the throwaway workdir so the MNIST-reading scripts can get real RUN
  legs (today they are compile-only: `jvm-compile` + `wasm-component`).
  `ch03/sample-weight.bin` is committed and small, so
  `neuralnet-mnist{,-batch}.lisp` would only additionally need the idx
  files (~55 MB, gitignored) -- maybe gate on their presence with an
  assumption-skip like the wasmtime check.
- **Full-scale runs**: the training scripts' defaults are scaled down
  (100-image subsets, one epoch for the CNNs). Book-fidelity settings are
  documented in each header; nothing blocks running them under
  `--simd`/JVM other than patience (and the im2col item above).
- **WASM exp/log parity**: loss prints still differ in their last digits
  on WASM (the known polynomial-approximation drift). If a later todo
  makes WASM's exp/log bit-identical, the two `contains:`-checked examples
  (`ch04/gradient-simplenet`, and the loss lines generally) can tighten to
  `file:` expects.
- ch03 `sig_step_compare`-style ASCII sparklines, PIL-grade image output
  (`ch03/mnist-show.lisp` renders ASCII art) -- nice-to-have only.
