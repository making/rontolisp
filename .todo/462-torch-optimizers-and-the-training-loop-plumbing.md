# 462. `torch` optimizers and the training-loop plumbing

Difficulty: Medium

Child of `.todo/458`, depends on `.todo/460` (and reads `torch:parameters` from
`.todo/461`). What turns a differentiable model into a training run.

## Surface

- **optimizers**: `torch:sgd` (`params &key lr momentum weight-decay`),
  `torch:adam` (`params &key lr betas eps` -- the book uses `Adam(lr=0.001)`),
  `torch:step`, `torch:zero-grad`. Both hold their state (momentum buffers, Adam's
  m/v and step count) inside the optimizer object, never on the parameter.
- **batching, as plain functions rather than a `Dataset`/`DataLoader` hierarchy**:
  `torch:pad-sequence` (list of index lists -> a padded rank-2 tensor, batch-first,
  `&key padding-value`), `torch:shuffled-batches` (a sequence + batch size ->
  batches, using the seeded `linalg` RNG so a run reproduces on every backend), and
  the padding/subsequent mask constructors the book defines
  (`torch:padding-mask`, `torch:subsequent-mask`) since both are pure tensor code
  and every attention program needs them.
- **inference**: `torch:no-grad` comes from `460`; add `torch:inference-mode` only
  if it turns out to differ.

## Design notes

- `torch:step` mutates the parameter's `data` array IN PLACE (`setf aref` over the
  packed array) rather than allocating a fresh one -- a fresh array per parameter
  per step is the allocation that dominates a small training loop, and the
  optimizer is outside the tape so there is no adjoint to preserve. Say so in
  `.kb/torch.md`.
- Adam's bias correction uses the optimizer's own step counter, not the parameter's
  -- pin `t=1` behaviour in a test, it is the classic off-by-one.
- The examples' existing hand-written `examples/deep-learning-from-scratch/common/optimizer.lisp`
  is the reference for the update rules (already cross-backend correct); do NOT
  make the example depend on the new package -- that book's point is that the
  reader writes them.

## Acceptance

- Update-rule tests with hand-computed expected values for one step of SGD,
  SGD+momentum and Adam, on **all four backends**.
- An end-to-end regression test: the `SkipConnection` vs `FFN` identity-learning
  experiment from `notebooks/chapter02/section3.ipynb`, small enough for CI, loss
  decreasing and reproducible from a fixed seed on every backend.
- Docs: reference pages + catalog + `functions.md` rows, and the training-loop
  section of `guides/neural-networks.md` (en + ja).

## Non-goals

LR schedulers, gradient clipping (add when a program needs it), AdamW, mixed
precision, checkpointing.
