# torch:cross-entropy-loss

`(torch:cross-entropy-loss logits targets &key ignore-index reduction)`

Returns the cross entropy over raw **logits** as a scalar tensor (PyTorch's `nn.CrossEntropyLoss`). The logits have shape `(... num-classes)` -- the leading axes are flattened, so `(batch seq vocab)` works directly. It is computed from `-log-softmax`, which is the numerically stable form; do **not** pass softmax outputs.

The target is read one of two ways:

- **class indices** of the matching leading shape -- a number, a list, an index vector or a tensor. The loss is `-log-softmax` picked at the target class.
- **class probabilities** -- a tensor or array of the logits' own shape, PyTorch's soft-label form. The loss is `-sum(target * log-softmax(logits))` per position, and the gradient flows into the target too when it requires one. A LIST is always class indices, so the probability spelling needs a tensor or an array.

`:ignore-index k` drops every position whose class-index target is `k` from both the sum and the mean's denominator, which is what keeps padding positions from contributing; like PyTorch it does not apply to probability targets. `:reduction :sum` adds instead of averaging and `:reduction :none` returns the per-position tensor.

```lisp
(torch:item (torch:cross-entropy-loss (torch:tensor '((0.0 0.0))) #(0)))
; => 0.6931471805599453
(torch:item (torch:cross-entropy-loss (torch:tensor '((0.0 0.0) (0.0 0.0)))
                                      #(0 1) :ignore-index 1))
; => 0.6931471805599453
(torch:item (torch:cross-entropy-loss (torch:tensor '((0.0 0.0)))
                                      (torch:tensor '((0.5 0.5)))))
; => 0.6931471805599453
```
