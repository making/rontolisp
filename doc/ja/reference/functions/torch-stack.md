# torch:stack

`(torch:stack tensors &key axis)`

リスト `tensors` を新しい軸に沿って積む微分可能な演算です (`linalg:stack`)。形は同じである必要があり、結果のランクは 1 増え、新しい軸は `:axis` の位置に入ります (負の値は結果の末尾から数えます)。backward は各入力のインデックスで勾配を切り出し、その軸を再び落とします。

```lisp
(torch:data (torch:stack (list (torch:tensor '(1.0 2.0)) (torch:tensor '(3.0 4.0)))))
; => #d((1.0 2.0) (3.0 4.0))
```
