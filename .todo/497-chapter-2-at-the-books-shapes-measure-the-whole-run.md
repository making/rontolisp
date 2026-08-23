# Chapter 2 at the book's shapes: the 20-epoch run is unmeasured, and its step unprofiled

Difficulty: Medium

Filed 2026-08-23 night. The chapter-2 Transformer (`examples/llm-from-scratch/chapter02/
section5.lisp`) at the book's configuration -- `d_model` 512, 6 blocks, 8 heads, `d_ff`
512, batch 64, `small_parallel_enja` -- runs at **0.35 s a batch** (2 epochs over 10000
pairs plus 20 greedy decodes in 124 s; loss 4.72 -> 3.61) on the GB10 under `--gpu --simd`
after `.todo/492`, from 1.9 s the day before. The book's own run is 20 epochs over 50000
pairs, 15640 batches: ~1.5 h projected, **not run** -- it was judged too long for the
session that measured it.

Two things this item wants:

- The run itself, once, so the README's row is a measurement (the loss it reaches, the
  greedy decodes it produces, the wall time), and whether it fits in the device budget
  over that length (the residency's `System.gc()` policy, `.kb/gpu.md`).
- A profile of the batch: this model is rank-3 `torch:matmul` per head with an explicit
  per-head loop and `torch:cat`, cross-entropy with `ignore-index`, and the encoder-decoder
  masks -- which members of it still run on the host (the `_gpuMaterialize` callers), and
  whether the 0.35 s is the same launch-bound story as chapter 3 (`.todo/496`) or has a
  host read of its own.

The corpus and the file variant live outside the repository (nothing here downloads):
`small_parallel_enja` cloned, `train.ja` / `train.en` beside the program, and the
`defparameter`s at the top of the file set to the book's values.
