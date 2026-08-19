# torch:mse-loss

`(torch:mse-loss input target &key reduction)`

Returns the mean squared error between input and target as a scalar tensor (PyTorch's `nn.MSELoss`). `:reduction :sum` adds instead of averaging and `:reduction :none` returns the per-element tensor. The target is a constant unless it is itself a tensor requiring gradients; either argument may be a number, a list or an array.

```lisp
(torch:item (torch:mse-loss (torch:tensor '(1.0 2.0)) '(0.0 0.0)))                 ; => 2.5
(torch:item (torch:mse-loss (torch:tensor '(1.0 2.0)) '(0.0 0.0) :reduction :sum)) ; => 5.0
```
