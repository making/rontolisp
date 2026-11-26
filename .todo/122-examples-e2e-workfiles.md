# ExamplesE2eTest: workFiles manifest field (RUN legs for file-reading examples)

Split out of todo-117 (deleted on completion 2026-07-13). E2E-infrastructure
item: examples that read data files USED to be compile-only
(`jvm-compile` + `wasm-component`), because the driver runs each program in a
throwaway workdir that has none of the example's files.

## What shipped 2026-07-23

Two new per-example manifest fields in `examples/examples.yaml`:

- `workDir` -- sub-dir under `examples/` the process runs from (default: none,
  i.e. the throwaway workdir itself). Set it when the script's CWD-relative
  reads assume a book root (e.g. `deep-learning-from-scratch/`); the leg's CWD
  becomes `work/<workDir>/`.
- `workFiles` -- paths (relative to `workDir` when set, otherwise `examples/`)
  copied 1:1 into the workspace, so the mirrored slice looks like the fragment
  of `examples/` the script was written against.

`stageWorkspace` in `ExamplesE2eTest.java` does the copy. A missing workFile
aborts the leg as a skipped assumption (same shape as the wasmtime-on-PATH
check), not a failure -- so CI without the gitignored idx dumps only exercises
the compile-only `wasm-component` leg, while a developer with the dumps run
gets the full cross-backend accuracy check. `workFiles` are only staged for RUN
backends (`interpreter`/`jvm`/`wasm`); compile-only legs never need them.

The RUN-leg upgrades that landed with the feature:

- `ch07/visualize-filter.lisp` -- workFiles: [ch07/params.bin]. Full
  `interpreter/jvm/wasm/wasm-component` coverage, byte-identical output pinned
  in `.expected/dlfs-visualize-filter.txt`. The intended first target.
- `ch03/mnist-show.lisp` -- workFiles: dataset/train-{images,labels}. Same
  four-backend coverage, `.expected/dlfs-mnist-show.txt` -- the RUN legs skip
  themselves when the gitignored dumps are absent.
- `ch03/neuralnet-mnist.lisp` + `neuralnet-mnist-batch.lisp` -- workFiles:
  ch03/sample-weight.bin + dataset/t10k-{images,labels}. Softmax + argmax
  gives the same classes on every backend, checked with `equals: "Accuracy:
  932/1000"`. Also skip themselves without the idx files.

## What still isn't a RUN leg

- ch08 (`misclassified-mnist.lisp`, `half-float-network.lisp`,
  `train-deepnet.lisp`) -- workFiles are cheap (deep-convnet-params.bin is
  committed, or the training scripts just need the dataset dumps), but the
  deep-convnet forward pass at `*test-limit* = 1000` is minutes per
  interpreter leg. Revisit when the forward pass shrinks or add a `*sampled*`
  override + smaller expected value.
- Everything under ch04/ch05/ch06 that reads `dataset/*-ubyte` -- the training
  scripts (`train-neuralnet`, `optimizer-compare-mnist`, `weight-init-compare`,
  `overfit-*`, `hyperparameter-optimization`, `batch-norm-test`). Same reason:
  they're wall-clock heavy even when the data is present. Also revisit with
  the ch08 shrink.

## Folded-in notes parked from todo-117

- **WASM exp/log parity**: loss prints still differ in their last digits on
  WASM (polynomial-approximation drift). If a later todo makes WASM's
  exp/log bit-identical, `ch04/gradient-simplenet` and the loss-printing
  training scripts can tighten `contains:` expects to `file:`.
- **Full-scale runs**: the training scripts' defaults are scaled down;
  book-fidelity knob settings are documented in each header. Nothing
  blocks them beyond patience -- not a work item, just recorded.
- Nice-to-have only: ch03 `sig_step_compare`-style ASCII sparklines,
  PIL-grade image output (`mnist-show` renders ASCII art), and ch07
  `apply_filter.py` (needs a PNG reader for `lena_gray.png`; revisit only
  if an image-file story appears).
