# torch:cat

`(torch:cat tensors &key axis)`

リスト `tensors` を既存の軸に沿って連結する微分可能な演算です (`linalg:concatenate`、`torch.cat`。デフォルトは 0、負の値は末尾から数えます)。backward は勾配をその軸に沿って各入力の広がりに切り分けて戻します。

```lisp
(torch:data (torch:cat (list (torch:tensor '(1.0 2.0)) (torch:tensor '(3.0))))) ; => #f(1.0 2.0 3.0)
```
