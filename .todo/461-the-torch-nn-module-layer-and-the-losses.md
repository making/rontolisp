# 461. The `torch` nn module layer and the losses

Difficulty: Medium

Child of `.todo/458`, depends on `.todo/460`. The objects the book's code is
written against: a module that owns parameters, composes, and has a `forward`.

## Surface

- **protocol**: `torch:module` (base), `torch:forward` (generic; a user layer is a
  `defclass` on `torch:module` plus a `defmethod torch:forward`),
  `torch:parameters` (every parameter reachable from a module, including through
  submodules and module lists -- this is what the optimizer in `462` consumes),
  `torch:parameter` (a leaf tensor with `requires-grad` t), `torch:zero-grad` on a
  module, and `(torch:call m args...)` / making a module funcallable so
  `(torch:forward m x)` reads like `self.attention(x, x, x)`.
- **layers**: `torch:linear` (`in-features out-features &key bias`),
  `torch:embedding`, `torch:relu`, `torch:sequential`, `torch:module-list`,
  `torch:layer-norm` (`d-model &key eps`), `torch:dropout` (train/eval aware --
  keep it, the book's later chapters use it).
- **losses**: `torch:mse-loss`, `torch:cross-entropy-loss` (logits + integer class
  targets, `&key ignore-index` -- padding positions must not contribute, which the
  chapter-02 translation training needs), both as ordinary functions returning a
  scalar tensor.
- **modes**: `torch:train` / `torch:eval` on a module (dropout, and nothing else
  for now).

## Design notes

- **Parameter registration must be automatic.** In PyTorch, assigning a submodule
  to an attribute registers it. The Lisp equivalent is that `torch:parameters`
  WALKS the module's slots and collects every tensor / module / list-of-modules it
  finds, rather than asking each layer to declare its parameters -- a layer that
  forgets to declare one trains silently wrong, which is the worst failure mode in
  this whole item. `.kb/clos.md` describes the slot layout available to do the walk.
- **Init matters for reproducing the book's numbers.** `nn.Linear` is Kaiming
  uniform (`U(-1/sqrt(in), 1/sqrt(in))` for both weight and bias); use `linalg`'s
  seeded Wichmann-Hill RNG (`linalg:seed`, `.kb/linalg.md`), which is bit-identical
  on all four backends, so a seeded training run reproduces everywhere.
- **`layer-norm` uses `ddof` 0** (`unbiased=False` in `utils.py`), i.e. the
  `linalg:std` default from `.todo/459`.
- **Every layer's forward is composed of `torch:` ops from `460`** -- no layer
  reaches into `linalg` directly, or its backward silently disappears.

## Acceptance

- Gradient check (the table from `460`) extended with one row per layer and loss:
  the module's analytic parameter gradients match numerical differentiation.
- A shape test per layer, and a "train 200 steps on a toy problem, loss goes down"
  test -- on **all four backends**; plus one `ci-spec.yaml` case.
- Docs: per-operator reference pages + `_catalog.yaml` + `functions.md` rows,
  `IndentRules` entries for any body-taking macro (`.kb/formatter.md`), and the
  module half of `guides/neural-networks.md` (en + ja).

## Non-goals

Conv/RNN layers, weight tying, parameter groups, `state_dict` save/load,
initialization schemes beyond the PyTorch defaults.
