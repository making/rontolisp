# Deep Learning from Scratch: ch07-ch08 (CNN) port

The MLP chapters (ch02-ch06) of ゼロから作るDeep Learning are ported under
`examples/deep-learning-from-scratch/` (see its README.md; the linalg axis
reductions / seeded RNG / indexing helpers, the CLOS layer library and the
MNIST idx loader all landed with that work). This todo is the follow-up:
ch07 (SimpleConvNet: im2col/col2im, Convolution and Pooling layers) and
ch08 (DeepConvNet, `awesome_net`, misclassified-MNIST, the float16 note).

## Missing linalg pieces (the gap analysis, ch07/ch08 column)

The book's CNN code needs, beyond what landed:

- **`linalg:transpose` with an axes argument** (numpy
  `x.transpose(0, 3, 1, 2)`): im2col permutes a 6-D scratch tensor with
  `transpose(0, 4, 5, 1, 2, 3)`, Convolution/Pooling flip NCHW <-> NHWC.
  The existing rank-2 transpose stays the 1-arg behavior; an optional axes
  list generalizes it (row-major odometer walk like `%la-bcast-loop`, or
  the simpler per-element multi-index remap).
- **`linalg:pad`** (numpy `np.pad(x, [(0,0) (0,0) (p,p) (p,p)])`,
  constant-0 mode is all the book uses).
- **Strided window read/accumulate**: im2col's
  `img[:, :, y:y_max:stride, x:x_max:stride]` slice read and col2im's
  `+=` slice accumulate. Options: (a) a dedicated
  `linalg::%la-im2col`/`%la-col2im` pair in linalg.lisp (rank-4 only, the
  pragmatic choice -- numpy has no im2col either, but the loops are pure
  index arithmetic and slow interpreted); (b) generic
  `linalg:slice`/`slice-set!` with strides (bigger API, reusable).
- Already covered by the ch02-ch06 work (rank-generic): axis reductions
  (`amax`/`argmax` along axis 1 for Pooling), `take-rows` (rank-4 batch
  extraction), `reshape -1`, `zeros-like`.

## Port shape

- `common/layers.lisp` gains `convolution` and `pooling` CLOS classes
  (forward caches col/col-w/argmax like the book; backward scatters
  through col2im). The one new scatter (`dmax[arange(size), argmax] = dout`
  in Pooling backward) can stay functional via `one-hot`-style
  construction or motivate a `linalg:scatter!` -- decide then (scatter was
  deliberately left out of the ch02-ch06 work).
- `ch07/simple-convnet.lisp` + `train-convnet.lisp` + `gradient-check.lisp`
  (small synthetic check like ch05/ch06's); `ch08/deep-convnet.lisp` +
  `train-deepnet.lisp`. `params.pkl` / `deep_convnet_params.pkl` re-export
  through `tools/export-sample-weight.py`'s RLW1 format (it already
  handles n-dim arrays; add a key-order argument).
- Runtime: a CNN forward is ~100x the MLP's. The interpreter leg is only
  practical under `--simd`; scale train subsets down hard (im2col turns
  conv into `linalg:matmul`, which IS `--simd`-intercepted, so the heavy
  lifting is already accelerated; im2col itself would run as scalar Lisp
  loops -- measure, and consider kernel interception per `.todo/107` if it
  dominates).

## Deferred items parked here from the ch02-ch06 work

- **ExamplesE2eTest `workFiles:` manifest field**: copy listed data files
  into the throwaway workdir so the MNIST-reading scripts can get real RUN
  legs (today they are compile-only: `jvm-compile` + `wasm-component`).
  `ch03/sample-weight.bin` is committed and small, so
  `neuralnet-mnist{,-batch}.lisp` would only additionally need the idx
  files (~55 MB, gitignored) -- maybe gate on their presence with an
  assumption-skip like the wasmtime check.
- **Full-scale runs**: the training scripts' defaults are scaled down
  (500-image subsets, small hidden sizes, few epochs). Book-fidelity
  settings are documented in each header; nothing blocks running them
  under `--simd`/JVM other than patience.
- **WASM exp/log parity**: loss prints still differ in their last digits
  on WASM (the known polynomial-approximation drift). If a later todo
  makes WASM's exp/log bit-identical, the two `contains:`-checked examples
  (`ch04/gradient-simplenet`, and the loss lines generally) can tighten to
  `file:` expects.
- ch03 `sig_step_compare`-style ASCII sparklines, PIL-grade image output
  (`ch03/mnist-show.lisp` renders ASCII art) -- nice-to-have only.
