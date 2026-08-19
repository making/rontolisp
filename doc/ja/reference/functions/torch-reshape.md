# torch:reshape

`(torch:reshape a shape)`

微分可能な reshape です (行優先、`linalg:reshape` の規則: サイズは一致が必要で、1 つの広がりに `-1` を書くと推論されます)。backward は勾配を入力の形に整形し直します。

```lisp
(torch:data (torch:reshape (torch:tensor '(1.0 2.0 3.0 4.0)) '(2 2))) ; => #d((1.0 2.0) (3.0 4.0))
```
