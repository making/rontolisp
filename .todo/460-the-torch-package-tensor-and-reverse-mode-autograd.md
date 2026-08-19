# 460. The `torch` package: the tensor and reverse-mode autograd

Difficulty: High

Child of `.todo/458`, depends on `.todo/459`. The core of the PyTorch-style layer:
a value that remembers how it was computed, and a `backward` that walks that
history to fill in gradients. Everything in `461` / `462` is built on this; get it
wrong and both are wrong.

A new Lisp-source library `src/main/resources/am/ik/rontolisp/eval/torch.lisp` +
`TorchLibrary.java`, following the `LinalgLibrary` pattern exactly (lazy load in
the interpreter via `LispEvaluator#resolveFunction`, splice on the compile path
when the program references the package). `LispNames` + `PackageRegistry`
(`TORCH_FUNCTIONS`) entries for every exported name.

## The tensor

```
data          a linalg array (packed float, any rank; :element-type honoured)
grad          nil, or an array of data's shape
requires-grad t/nil
parents       the input tensors this one was computed from
backward-fn   a closure: (lambda (grad-out) ...) -> the per-parent gradients
```

Open decision, and the one to settle FIRST: how the record is expressed.
`.kb/library-defun-pruning.md` prunes only `defun`/`defparameter`/`defvar`/
`defconstant` in rontolisp's own libraries, so a `defclass`-based tensor makes the
WHOLE torch library non-prunable -- every program that touches `torch:softmax`
carries every optimizer and every module. Either build the tensor out of plain
defuns over a fixed-layout record and keep the CLOS to `461`'s modules only, or
widen the pruner to the CLOS definition kinds in this item. Decide explicitly;
record the choice and the reason in `.kb/torch.md`.

## Surface

- construction / access: `torch:tensor` (from a list, a built-in array or a linalg
  array; `&key requires-grad element-type`), `torch:data`, `torch:grad`,
  `torch:shape`, `torch:detach`, `torch:item`, `torch:zero-grad`
- shape ops: `reshape`, `view`, `transpose` (axes list), `unsqueeze`, `squeeze`,
  `cat`, `stack`, `slice`
- arithmetic: `add`, `sub`, `mul`, `div`, `neg`, `power`, `exp`, `log`, `sqrt`,
  `tanh`, `matmul` (batched), `sum`, `mean`, `var`, `std`, `amax`, `argmax`
  (non-differentiable, returns indices), `softmax`, `log-softmax`, `relu`,
  `masked-fill`, `gather`, `index-select` (the embedding lookup)
- autograd: `torch:backward` (on a scalar tensor; seeds 1.0), `torch:no-grad`
  (macro -- needs an `IndentRules` entry, `.kb/formatter.md`), `torch:requires-grad-p`

## The two things that are easy to get wrong

1. **Broadcasting adjoints.** When `linalg:add` broadcasts a `(d)` bias against an
   `(b s d)` activation, the bias's gradient is the incoming gradient SUMMED over
   the broadcast axes and reshaped back. Every elementwise op needs that
   reduction; skipping it silently trains the wrong shape or crashes far away from
   the cause. Write one `%grad-unbroadcast grad target-shape` helper and route
   every elementwise backward through it.
2. **Traversal order.** `backward` must accumulate (`+=`, not `=`) into a tensor
   reached by more than one path -- a residual connection `x + sublayer(x)` and a
   shared embedding table both do this -- and must visit nodes in reverse
   topological order, not in tape order. Do the topo sort explicitly.

## Acceptance

- **Gradient check against `linalg:gradient`**: for every differentiable op, the
  analytic gradient matches central-difference numerical differentiation to a
  tolerance. This is the real test of this item; write it as a table-driven test so
  each new op in `461` extends it with one row.
- Runs on **all four backends** (`LispEvaluatorTest`, `JvmLispCompilerTest`,
  `WasmLispCompilerIntegrationTest`, plus a `ci-spec.yaml` case for a small
  end-to-end "fit y = 2x" loop). The closures the tape is made of are the part most
  likely to diverge on wasm -- exercise a multi-step graph there, not just one op.
- `.kb/torch.md` written: the tensor layout, the pruning decision, the adjoint
  rules table, and the "runs on all four backends" pin.
- Docs: `reference/packages.md` entry, per-function reference pages, and the first
  half of the `guides/neural-networks.md` guide (en + ja).

## Non-goals

Higher-order gradients (`create_graph`), in-place ops with autograd, sparse
gradients, `torch.func`-style transforms, GPU.
