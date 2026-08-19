# torch:layer-norm

`(torch:layer-norm d-model &key eps)`

Returns a layer-normalization layer over the last axis (PyTorch's `nn.LayerNorm`): fields `:weight` (a `(d-model)` parameter of ones), `:bias` (a `(d-model)` parameter of zeros) and the `:eps` hyper-parameter, `1.0e-5` by default. The forward is `(x - mean) / sqrt(var + eps) * weight + bias` with the **biased** variance (`ddof` 0, PyTorch's `unbiased=False`).

The whole expression is composed from `torch` operations, so the normalization itself is differentiable -- the gradient flows through the mean and the variance too, not just through the affine parameters.

```lisp
(torch:data (torch:forward (torch:layer-norm 2 :eps 0.0)
                           (torch:tensor '((1.0 3.0)))))  ; => #d((-1.0 1.0))
```
