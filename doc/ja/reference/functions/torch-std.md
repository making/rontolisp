# torch:std

`(torch:std a &key axis keepdims ddof)`

微分可能な標準偏差、すなわち [`torch:var`](torch-var.md) の [`torch:sqrt`](torch-sqrt.md) です。1 つの軸に沿った平均と標準偏差は LayerNorm が必要とする 2 つの統計量です。

```lisp
(torch:item (torch:std (torch:tensor '(2.0 4.0 4.0 4.0 5.0 5.0 7.0 9.0)))) ; => 2.0
```
