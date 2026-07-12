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

- **im2col `--simd` interception** (the measured bottleneck): im2col/
  col2im run as scalar Lisp loops and dominate the accelerated runs --
  ch07 train is 88s under interpreter `--simd` vs 2.2s on the JVM, so
  ~97% of the accelerated interpreter time is the unfold, not the
  matmul. An `eval.LinalgSimd`-style native for `%la-im2col`/`%la-col2im`
  (and the JVM/wasm-GC call-site equivalents per `.todo/107`) would close
  most of that gap. Kernels are pure index arithmetic -- no lanes needed,
  just compiled loops.
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
