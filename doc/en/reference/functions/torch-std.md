# torch:std

`(torch:std a &key axis keepdims ddof)`

Differentiable standard deviation: [`torch:sqrt`](torch-sqrt.md) of [`torch:var`](torch-var.md). The mean and std along one axis are the two statistics LayerNorm needs.

```lisp
(torch:item (torch:std (torch:tensor '(2.0 4.0 4.0 4.0 5.0 5.0 7.0 9.0)))) ; => 2.0
```
