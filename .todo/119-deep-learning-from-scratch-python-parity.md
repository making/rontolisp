# Deep Learning from Scratch: systematic parity check against the Python original

The rontolisp port (`examples/deep-learning-from-scratch/`) should be
verifiable against the book's own numpy code
(`/Users/toshiaki/git/deep-learning-from-scratch`, available locally).

## Already verified (2026-07-12, ad hoc)

- **ch03 inference, per-image**: the book's forward pass run in numpy over
  the SAME first 1000 t10k images and the SAME pretrained weights
  (sample_weight.pkl) agrees with the port's predictions on **1000/1000
  images** (both 932/1000 accurate) -- matmul + bias broadcast + sigmoid +
  softmax + argmax(axis=1) are numerically interchangeable end to end.
- The deterministic scripts (ch02 truth tables, ch04 gradient-1d/2d/
  method, ch05 buy-apple values) print the book's exact numbers by
  construction.
- Gradient checks (ch05, ch06 BatchNorm) pass on both sides independently
  (backprop == central differences), which transitively pins the layer
  backward passes.

## What a systematic harness would add

A `tools/parity-check.py` (book repo + numpy required) that:

1. **Fixed-weight loss/gradient parity**: export a small seeded rontolisp
   net's params via the RLW1 binary format (the export/import path already
   exists in `tools/export-sample-weight.py` / `load-sample-weight`), load
   them into the book's `TwoLayerNet` / `MultiLayerNet(Extend)`, feed an
   identical fixed batch (also exported), and compare loss + every
   grads[key] elementwise (tolerance ~1e-9 relative for exp/log paths,
   exact for the affine-only parts). This checks Affine/Relu/Sigmoid/
   SoftmaxWithLoss/BatchNorm/Dropout(eval-mode)/weight-decay backward
   against the original implementation, not just against numerical
   differentiation.
2. **Optimizer step parity**: apply each of SGD/Momentum/Nesterov/AdaGrad/
   RMSprop/Adam to identical params/grads snapshots for a few steps and
   compare trajectories elementwise (pure arithmetic + sqrt/expt -> should
   match to the last ulp or so; Adam's lr_t uses expt).
3. A rontolisp-side dump mode per net (a small common/dump.lisp writing
   RLW1 files of params/grads/batches) to feed (1)/(2).

## Caveat to encode in the harness

Training RUNS can never match end-to-end: the port's RNG is Wichmann-Hill
+ Irwin-Hall (chosen for cross-backend bit-identity; see
`doc/en/reference/functions/linalg-randn.md`), numpy's is Mersenne Twister
+ Gaussian ziggurat -- so weight init and batch sampling differ by design.
Parity is checked at fixed inputs, not across whole training runs;
training-quality comparisons are statistical only (final accuracies in the
same ballpark).
