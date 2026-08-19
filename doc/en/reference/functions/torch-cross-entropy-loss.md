# torch:cross-entropy-loss

`(torch:cross-entropy-loss logits targets &key ignore-index reduction)`

Returns the cross entropy over raw **logits** and integer class targets as a scalar tensor (PyTorch's `nn.CrossEntropyLoss`). The logits have shape `(... num-classes)` -- the leading axes are flattened, so `(batch seq vocab)` works directly -- and the targets the matching leading shape. It is computed as `-log-softmax` picked at the target class, which is the numerically stable form; do **not** pass softmax outputs.

`:ignore-index k` drops every position whose target is `k` from both the sum and the mean's denominator, which is what keeps padding positions from contributing. `:reduction :sum` adds instead of averaging and `:reduction :none` returns the per-position tensor.

```lisp
(torch:item (torch:cross-entropy-loss (torch:tensor '((0.0 0.0))) #(0)))
; => 0.6931471805599453
(torch:item (torch:cross-entropy-loss (torch:tensor '((0.0 0.0) (0.0 0.0)))
                                      #(0 1) :ignore-index 1))
; => 0.6931471805599453
```
