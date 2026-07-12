# Deep Learning from Scratch, in rontolisp

A rontolisp port of [the sample code of the book **"Deep Learning from
Scratch"** (ゼロから作るDeep Learning, O'Reilly Japan) by Koki Saitoh](https://github.com/oreilly-japan/deep-learning-from-scratch) —
chapters 2-8: perceptrons, MNIST inference, numerical gradients,
backpropagation through class-based layers, the training techniques
chapter (optimizers, weight initialization, Batch Normalization, Dropout,
weight decay, hyperparameter search), and the CNN chapters (im2col
convolution/pooling layers, SimpleConvNet, the ch08 deep network). The
original code is MIT-licensed (see [LICENSE.md](LICENSE.md)).

The port maps numpy to the `linalg:` package (axis reductions, broadcasting,
`linalg:randn`/`choice` seeded RNG, `take-rows`/`gather`/`one-hot`
indexing), Python classes to the CLOS subset (`defclass` layers with
`forward`/`backward` generics), and `params`/`grads` dicts to string-keyed
hash tables. matplotlib plots become printed tables, trajectories and text
histograms. All arrays are double-float (`#d`), so adding `--simd` speeds a
script up **without changing a byte of its output**.

## Setup

```bash
./download-mnist.sh    # fetches + decompresses the 4 MNIST idx files (~55 MB) into dataset/
```

`ch03/sample-weight.bin` (the book's pretrained `sample_weight.pkl`,
re-exported by `tools/export-sample-weight.py`) is already committed.

## Running

Everything runs from this directory on all four backends. With the native
binary (or `java -jar $JAR`):

```bash
rontolisp ch05/train-neuralnet.lisp              # interpreter
rontolisp ch05/train-neuralnet.lisp --simd       # same output, much faster

rontolisp ch05/train-neuralnet.lisp -o Prog.class && java -cp .:$JAR_CLASSPATH Prog

rontolisp ch05/train-neuralnet.lisp -o prog.wasm --optimize && \
  wasmtime run -W gc --dir . prog.wasm

rontolisp ch05/train-neuralnet.lisp -o comp.wasm --component && \
  wasmtime run -W gc=y -W component-model-more-async-builtins=y --dir . comp.wasm
```

The MNIST scripts read `dataset/*-ubyte` relative to this directory, so the
WASM backends need the `--dir .` preopen. Training scripts declare their
knobs (`*train-limit*`, `*batch-size*`, `*epochs*`, ...) as `defparameter`s
at the top — the defaults are scaled down from the book's (500 instead of
60000 train images, etc.) so the plain interpreter finishes in a few
minutes per script (the ch06 comparison/overfitting scripts take the
longest, roughly 4-8 minutes; the same runs are seconds on the JVM or
under `--simd`); raise the knobs accordingly. Weight
initialization and mini-batch sampling use `linalg:seed`, so they reproduce
bit-identically everywhere; only values that pass through `exp`/`log` (loss
prints) can differ in their last digits on WASM.

## The programs

| Book | Port | What it shows |
| --- | --- | --- |
| ch02 `and_gate.py` ... | `ch02/{and,nand,or,xor}-gate.lisp` | Perceptrons; XOR needs a second layer |
| ch03 activation plots | `ch03/activation-functions.lisp` | step/sigmoid/relu as a printed table |
| ch03 `mnist_show.py` | `ch03/mnist-show.lisp` | The first training digit as ASCII art |
| ch03 `neuralnet_mnist.py` | `ch03/neuralnet-mnist.lisp` | Inference with the book's pretrained weights, one image at a time |
| ch03 `neuralnet_mnist_batch.py` | `ch03/neuralnet-mnist-batch.lisp` | The same, 100 images per matrix product |
| ch04 `gradient_1d.py` / `gradient_2d.py` | `ch04/gradient-{1d,2d}.lisp` | Numerical differentiation |
| ch04 `gradient_method.py` | `ch04/gradient-method.lisp` | Gradient descent, incl. bad learning rates |
| ch04 `gradient_simplenet.py` | `ch04/gradient-simplenet.lisp` | The numerical gradient of a softmax loss |
| ch04 `two_layer_net.py` + `train_neuralnet.py` | `ch04/two-layer-net.lisp` + `ch04/train-neuralnet.lisp` | MNIST training with sigmoid-grad backprop |
| ch05 `layer_naive.py` + `buy_apple*.py` | `ch05/layer-naive.lisp` + `ch05/buy-apple{,-orange}.lisp` | Computational-graph layers (first CLOS classes) |
| ch05 `two_layer_net.py` | `ch05/two-layer-net.lisp` | Affine/ReLU/SoftmaxWithLoss layer objects |
| ch05 `gradient_check.py` | `ch05/gradient-check.lisp` | Backprop vs numerical gradients (< 1e-6) |
| ch05 `train_neuralnet.py` | `ch05/train-neuralnet.lisp` | Training through the layer stack |
| ch06 `optimizer_compare_naive.py` | `ch06/optimizer-compare-naive.lisp` | SGD/Momentum/AdaGrad/Adam trajectories |
| ch06 `optimizer_compare_mnist.py` | `ch06/optimizer-compare-mnist.lisp` | The same four racing on MNIST |
| ch06 `weight_init_activation_histogram.py` | `ch06/weight-init-activation-histogram.lisp` | Saturation/collapse/Xavier, as text histograms |
| ch06 `weight_init_compare.py` | `ch06/weight-init-compare.lisp` | std=0.01 stalls; Xavier/He learn |
| ch06 `batch_norm_gradient_check.py` | `ch06/batch-norm-gradient-check.lisp` | The BatchNorm backward pass, verified |
| ch06 `batch_norm_test.py` | `ch06/batch-norm-test.lisp` | BN learns even from starved init scales |
| ch06 `overfit_weight_decay.py` | `ch06/overfit-weight-decay.lisp` | 300-image overfitting; L2 decay caps it |
| ch06 `overfit_dropout.py` | `ch06/overfit-dropout.lisp` | The same, tamed by Dropout |
| ch06 `hyperparameter_optimization.py` | `ch06/hyperparameter-optimization.lisp` | Random search over lr / weight decay |
| ch07 `simple_convnet.py` | `ch07/simple-convnet.lisp` | Conv-Relu-Pool-Affine-Relu-Affine over im2col |
| ch07 `gradient_check.py` | `ch07/gradient-check.lisp` | Convolution/Pooling backprop, verified (< 1e-6) |
| ch07 `train_convnet.py` | `ch07/train-convnet.lisp` | Training the SimpleConvNet with Adam |
| ch08 `deep_convnet.py` | `ch08/deep-convnet.lisp` | The 16-16/32-32/64-64 pyramid, He init, Dropout |
| ch08 `train_deepnet.py` | `ch08/train-deepnet.lisp` | Training the deep CNN (smoke-scale defaults) |

Shared library files mirror the book's `common/`: `functions.lisp`
(softmax, cross-entropy, ...), `gradient.lisp` (numerical gradients),
`layers.lisp` (CLOS layers incl. Convolution/Pooling, BatchNormalization
and Dropout), `util.lisp` (im2col/col2im), `optimizer.lisp`
(SGD/Momentum/Nesterov/AdaGrad/RMSprop/Adam), `multi-layer-net.lisp`,
`multi-layer-net-extend.lisp`, `trainer.lisp`, and `dataset/mnist.lisp`
(the idx / binary-weight loaders).

The CNN scripts are the heavy ones: a convolution forward is ~100x an
MLP's arithmetic, and while im2col turns it into `linalg:matmul` (which
`--simd` accelerates), the im2col unfold itself runs as scalar Lisp
loops. `ch07/train-convnet.lisp` at its scaled-down defaults is ~5
minutes interpreted, ~90 seconds under `--simd`, and ~2 seconds compiled
to the JVM.
