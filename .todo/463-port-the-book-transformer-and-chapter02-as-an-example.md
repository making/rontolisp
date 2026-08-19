# 463. Port the book's Transformer and chapter 02 as an example

Difficulty: Medium

Child of `.todo/458`, depends on `.todo/461` and `.todo/462`. The acceptance test
for the whole parent item: the PyTorch code in
`../book-llm-from-scratch/llm_from_scratch/transformer/` and
`../book-llm-from-scratch/notebooks/chapter02/` rewritten in rontolisp, running on
the backends it declares.

## What to port

New `examples/llm-from-scratch/`:

- `transformer/attention.lisp` -- `dot-product-attention`,
  `scaled-dot-product-attention` (with mask), `attention-head`,
  `multi-head-attention`
- `transformer/utils.lisp` -- `layer-norm`, `sinusoidal-position-encoding`,
  `positional-encoding`
- `transformer/transformer.lisp` -- `encoder-block`, `encoder`, `decoder-block`,
  `decoder`, `transformer`, and the greedy `inference` loop
- `chapter02/section2.lisp` -- the numpy attention over n unit vectors: output
  vector, attention weights, their sum, the long-vector experiment, and the
  scaled-vs-unscaled softmax comparison. Pure `linalg`, no autograd -- it doubles
  as the proof that `.todo/459` alone is useful.
- `chapter02/section3.lisp` -- embedding shapes, the positional-encoding dot
  products, the FFN-vs-SkipConnection identity training, the LayerNorm check
- `chapter02/section4.lisp` -- cross entropy between two discretised Gaussians,
  against a uniform, and the one-hot case
- `chapter02/section5.lisp` -- padding and subsequent masks, vocabulary building,
  and a SMALL end-to-end ja->en training + greedy decode

The plots are the one thing that does not port; print the numbers the plot was
made of instead (that is what makes the example testable). Do not vendor
`small_parallel_enja` -- ship a tiny in-repo corpus, or generate one, so the
example is hermetic.

## Acceptance

- `examples/examples.yaml` entries with per-example backends. The pure-`linalg`
  sections run everywhere; the training ones declare what actually finishes in CI
  time -- interpreter + JVM at minimum, and measure before claiming the WASM legs
  (`.kb/wasm-*`). Run locally with
  `./mvnw -Dtest=ExamplesE2eTest -Drontolisp.examples=true -Drontolisp.examples.only=llm-from-scratch test`.
- Output is deterministic from a fixed `linalg:seed` -- the RNG is bit-identical
  across backends (`.kb/linalg.md`), so the expected output is one text for all of
  them. Keep printed values integer-valued or short-terminating where possible;
  double-float digits differ between JVM and WASM.
- `examples/llm-from-scratch/README.md` explaining what maps to what in the book,
  linking `size-report/results/*.md` rather than quoting any byte count
  (`CLAUDE.md`).
- Sources formatted with `rontolisp format` before the test run.

## Watch for

- **Speed.** A d_model=512 / 6-block Transformer will not train in CI. Ship the
  book's shapes as the DOCUMENTED example and a shrunken configuration as the
  tested one, and say which is which.
- This port is where the torch layer's missing pieces will surface. A gap found
  here is a change to `459`-`462` and their `.kb` files, not a workaround in the
  example -- an example that reaches around the library is the signal the library
  is wrong.
