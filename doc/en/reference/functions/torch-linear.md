# torch:linear

`(torch:linear in-features out-features &key bias)`

Returns a fully connected layer (PyTorch's `nn.Linear`): the field `:weight` is an `(in-features out-features)` parameter and `:bias` an `(out-features)` parameter, or `nil` under `:bias nil`. The forward is `x . W (+ b)`, so the bias broadcasts over every leading axis and an input of rank 3 is transformed batch-wise.

Both parameters are drawn from PyTorch's default `U(-1/sqrt(in-features), 1/sqrt(in-features))` using the seeded [`linalg:seed`](linalg-seed.md) generator, so a seeded run reproduces on every backend. The weight is stored `(in out)` -- not PyTorch's transposed `(out in)` -- so the forward is a plain [`torch:matmul`](torch-matmul.md).

```lisp
(defparameter *lin* (torch:linear 3 2))
(torch:set-field *lin* :weight (torch:parameter '((1.0 0.0) (0.0 1.0) (1.0 1.0))))
(torch:set-field *lin* :bias (torch:parameter '(0.5 -0.5)))
(torch:data (torch:forward *lin* (torch:tensor '((1.0 2.0 3.0)))))  ; => #d((4.5 4.5))
(torch:shape (torch:forward *lin* (torch:tensor (linalg:ones '(2 4 3))))) ; => (2 4 2)
```
