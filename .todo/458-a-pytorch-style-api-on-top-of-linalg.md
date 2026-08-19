# 458. A PyTorch-style API (the `torch` package) on top of linalg

Difficulty: High

Parent item. rontolisp ships a numpy-style array layer (`linalg`, `.kb/linalg.md`)
and a packed-vector kernel layer (`vec`, `.kb/vec.md`), but nothing above them:
every neural net written so far (`examples/deep-learning-from-scratch/`,
`examples/llama2/`) hand-codes its own layer objects, its own backward pass and its
own optimizer. A PyTorch-style layer -- a tensor that records how it was computed,
`backward`, `nn`-style modules, an optimizer -- is what turns those into ten-line
programs, and it is the surface every modern LLM tutorial is written against.

**Coverage target for this round**: `../book-llm-from-scratch/llm_from_scratch/transformer/`
(`attention.py`, `transformer.py`, `utils.py`) and `../book-llm-from-scratch/notebooks/chapter02/`
(sections 2-5). That is: scaled dot-product attention, multi-head attention, an
encoder/decoder Transformer, sinusoidal positional encoding, LayerNorm, an
embedding table, padding / subsequent masks, cross-entropy, MSE, Adam, and a real
(small) training loop plus greedy inference. Not "all of PyTorch".

## Where the functions go (the question this item answers)

Two layers, split by whether the operation is stateless array math or a
differentiable/stateful object:

- **numpy-parity ndarray ops stay in `linalg`** -- batched (rank >= 3) `matmul`,
  `concatenate`/`stack`, `expand-dims`/`squeeze`, `triu`/`tril`, `var`/`std`,
  `where`, basic slicing, `power`. numpy has every one of these; adding them to
  `linalg` closes real numpy gaps, and it keeps ONE implementation of the array
  math -- the one the `--simd` interceptors already pattern-match
  (`.kb/linalg-simd.md`). A second copy inside the torch layer would fork it. →
  `.todo/459`.
- **the differentiable layer is a NEW package, `torch`** -- a tensor carrying
  `requires-grad` / `grad` / the autograd tape, the `nn` modules with their
  parameters, and the optimizers. This is not numpy: it is stateful (parameters
  mutate, gradients accumulate) and object-based (CLOS). Putting it in `linalg`
  would break that package's "stateless array in, fresh array out" contract, which
  is exactly what makes `linalg` foldable and SIMD-interceptable. → `.todo/460`,
  `461`, `462`.

**One flat `torch` package**, not `torch` + `nn` + `optim`. rontolisp package names
are flat and globally visible; `nn:` and `optim:` are too generic to own, and
PyTorch's own three-way split is a Python import convenience, not a naming
distinction users depend on (unlike `uiop/filesystem`, which exists upstream and
is spelled that way in real code). So: `torch:tensor`, `torch:matmul`,
`torch:linear`, `torch:layer-norm`, `torch:cross-entropy-loss`, `torch:adam`,
`torch:backward`, `torch:no-grad`.

`torch:matmul` is the DIFFERENTIABLE op on tensors; `linalg:matmul` stays the raw
array op. Same name in two packages is the intended split, not a collision.

## Order

```
459 (linalg rank-N gaps)  ->  460 (tensor + autograd)  ->  461 (nn modules + losses)
                                                       ->  462 (optimizers + training plumbing)
                                       461, 462        ->  463 (port the book's transformer + chapter02)
```

459 is independently useful (it is pure numpy parity) and lands first. 461 and 462
both depend on 460 and can land in either order.

## Cross-cutting concerns to settle while doing 460

- **`LibraryDefunPruner` prunes only `defun`/`defparameter`/`defvar`/`defconstant`
  for rontolisp's own libraries** (`.kb/library-defun-pruning.md`). A `torch`
  package built from `defclass`/`defmethod` is therefore spliced WHOLE into every
  program that touches it -- the module stack, every loss, every optimizer, on a
  program that only wanted `torch:softmax`. Decide in 460: either express the
  module protocol with plain defuns + `defstruct`-free records so the existing
  pruner covers it, or widen the pruner to the CLOS definition kinds (a defclass
  and its methods are reachable iff the class name or a method name is
  referenced). Do not let it default into "always spliced".
- **`--simd`**: the torch layer must bottom out in the `linalg` kernels the
  interceptors already know (`compiler.LinalgKernelCallLayout`), spelling literal
  keywords at the call site, so a torch program is accelerated for free. Any new
  linalg kernel from 459 that dominates a transformer forward pass (batched
  matmul) should be considered for its own interceptor -- measure first.
- **All four backends.** Training is slow on WASM; `examples.yaml` for 463 declares
  which backends each program runs on, but every UNIT-level torch test runs on all
  four (interpreter, JVM, wasm-GC, `--component`).
- **Docs**: one new guide (`doc/en|ja/guides/neural-networks.md`), per-operator
  reference pages + `_catalog.yaml` entries + a `reference/functions.md` row for
  every exported name, a `reference/packages.md` entry, and `.kb/torch.md` as the
  invariant file. Mirrored en/ja in the same commit.
- **`IndentRules`**: `torch:no-grad` (and any other body-taking macro) needs a
  formatter entry, `.kb/formatter.md`.

## Non-goals for this round

GPU/threads, `torch.compile`, checkpoint save/load, RNN/CNN modules (the
`examples/deep-learning-from-scratch/` stack already covers convolution with its
own hand-written backward), distributed anything, and a `Dataset`/`DataLoader`
class hierarchy -- 462 provides batching as plain functions instead.
