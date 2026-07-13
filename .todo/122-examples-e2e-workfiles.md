# ExamplesE2eTest: workFiles manifest field (RUN legs for file-reading examples)

Split out of todo-117 (deleted on completion 2026-07-13). E2E-infrastructure
item: examples that read data files are compile-only today
(`jvm-compile` + `wasm-component`), because the driver runs each program in
a throwaway workdir that has none of the example's files.

## The idea

A per-example `workFiles:` list in `examples/examples.yaml`: paths
(relative to `examples/`) the driver copies into the workdir before the RUN
legs, preserving relative layout so the scripts' CWD-relative reads
(`dataset/...`, `ch07/params.bin`) work. WASM legs already pass `--dir .`.

Candidates, in order of value:

- `deep-learning-from-scratch/ch07/visualize-filter.lisp`: needs ONLY the
  committed `ch07/params.bin` (1.7 MB) -- and its output is byte-identical
  on every backend, so it can get full `interpreter`/`jvm`/`wasm` RUN legs
  with a `file:` expect. The best first target.
- `ch03/neuralnet-mnist{,-batch}.lisp`: committed `ch03/sample-weight.bin`
  plus the gitignored idx files (~55 MB, `./download-mnist.sh`) -- gate on
  file presence with an assumption-skip like the existing wasmtime check.
- `ch08/misclassified-mnist.lisp` / `half-float-network.lisp`: idx files +
  committed `ch08/deep-convnet-params.bin`; byte-identical output, but a
  deep-convnet forward @1000 is minutes-per-leg (todo-121 would shrink the
  interpreter leg) -- consider a small `*sampled*` override or skip.

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
