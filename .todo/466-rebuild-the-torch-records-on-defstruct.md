# Rebuild the `torch` records on `defstruct`

Difficulty: Medium

Depends on `.todo/465` (a bundled-library `defstruct` must prune per accessor
first; landing this on top of an all-or-nothing keyed candidate is a size
regression -- see 465's design section).

`torch` has three hand-rolled fixed-layout records, each a general vector with a
tag symbol in slot 0 (`.kb/torch.md`, `torch.lisp` header comment):

| record | slots | tag |
| --- | --- | --- |
| tensor | 6 (data, grad, requires-grad, parents, backward-fn) | `torch::%tensor` |
| module | 5 (kind, fields, forward-fn, training) | `torch::%module` |
| optimizer | 6 (kind, params, fields, step-fn, step-count) | `torch::%optimizer` |

They exist in that shape ONLY because the pruner could not prune a `defstruct`.
`.kb/torch.md` states the price explicitly: "If 461 ever finds a genuine need for
CLOS in torch, the price is widening the pruner's own-library scope to the CLOS
kinds first". 465 pays it -- for `defstruct`, which is what this layer actually
wants. **CLOS is still the wrong tool here and stays out**: the tensor has one
concrete type and nothing dispatches on it (the binary ops normalize
tensor/number/raw-array through `torch::%t-wrap` instead of a method matrix, and
`torch:forward` dispatches through the module's own closure precisely so a
`torch:sequential` can hold a bare `lambda` with no wrapper layer existing).

## What this buys

- **Printing.** `print` on a tensor currently shows the raw six-element vector,
  one slot of which is a closure whose printed form is backend-dependent -- which
  is why `.kb/torch.md` has to forbid examples and cross-backend pins from
  printing a tensor at all. `(:print-object ...)` (`.kb/defstruct.md`) makes it
  `#<TENSOR ...>` on all four backends and retires that rule.
- **`torch:tensorp` stops allocating.** It is
  `(and (arrayp x) (not (stringp x)) (equal (array-dimensions x) '(6)) (eq (row-major-aref x 0) 'torch::%tensor))`
  -- a fresh list per call, on the hottest predicate in the package (every op
  entry runs it through `%t-wrap`). A generated predicate is a tag compare.
- **`torch::%m-fields-slot` disappears.** It returns 2 for a module and 3 for an
  optimizer -- a slot NUMBER recovered by type test, so that `torch:field` /
  `torch:set-field` can serve both records. Named accessors remove the
  construct.
- **The tag-vs-length discrimination note goes away.** The comment "the optimizer
  record of todo-462 is six slots too, which is why the TAG, not the length, is
  the discriminator" is bookkeeping for a hazard `defstruct` does not have.

## Constraints

- **The public API does not change.** `torch:tensorp`, `torch:data`,
  `torch:grad`, `torch:shape`, `torch:item`, `torch:field`, `torch:parameters`,
  ... stay exactly as they are; only their bodies change. This is an internal
  representation swap, not a breaking change. Nothing outside `torch.lisp` may
  learn the accessor names.
- **No `:type (vector ...)`.** The slots are heterogeneous (a linalg array, a
  closure, a list, a flag), so the packed-integer arm of `.kb/defstruct.md` does
  not apply; the instance stays a general vector, the same shape as today. There
  is no runtime-performance change to argue about beyond the predicate.
- **Still no `defclass`/`defmethod` in `torch.lisp`.** Update the header comment
  and `.kb/torch.md` to say `defstruct` is now allowed and WHY (465), and that
  CLOS is still excluded for the dispatch reason above, not for the pruning one.
- The `--no-gc` backend rejects `defstruct` outright, but it also rejects
  anything but `(defun ...)` at top level, so `torch` was never reachable there.
  Confirm, do not assume.

## Acceptance

- `TorchGradcheck` unchanged and green on the interpreter, the JVM and wasm-GC;
  the ci-spec `torch-fit-cross-backend` / `torch-nn-cross-backend` /
  `torch-optim-cross-backend` cases byte-identical against the native binary.
- `examples/llm-from-scratch` still reproduces the book's 1.5453 / 1.9454 /
  1.2879 and 8/8, on every backend `examples.yaml` declares for it.
- `LibraryDefunPrunerTest.keepsOnlyTheTransitiveClosureOfTheCalledTorchFunction`
  still holds: a `torch:tensor`-only program drops `torch:backward` and
  `torch:matmul` AND the unused linalg members -- now with the record's own
  accessors pruning with them.
- Size: no regression against `size-report/results/*.md` for the torch-bearing
  programs. This is 465's real proof; measure both sides.
- A printed tensor is identical on all four backends (new pin -- the thing the
  current representation could not have).
