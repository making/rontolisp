# torch:amax

`(torch:amax a &key axis keepdims)`

微分可能な最大値です。全要素、または軸に沿って計算します (`linalg:amax` の規則)。勾配は最大値に等しいすべての要素に流れ、同値の間では均等に分配されます (PyTorch の `amax` の規則)。

```lisp
(torch:item (torch:amax (torch:tensor '(1.0 5.0 3.0))))                     ; => 5.0
(torch:data (torch:amax (torch:tensor '((1.0 4.0) (3.0 2.0))) :axis 1))      ; => #d(4.0 3.0)
```
