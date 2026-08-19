# torch:power

`(torch:power a b)`

微分可能な要素ごとの `a ** b` (`linalg:power`) です。どちらのオペランドもスカラーで構いません。基数の勾配は `g * b * a^(b-1)`、指数の勾配 (指数が勾配を追跡するときにだけ計算されます) は `g * a^b * ln a` で、正の基数に対してのみ意味を持ちます。

```lisp
(torch:data (torch:power (torch:tensor '(2.0 3.0)) 2)) ; => #d(4.0 9.0)
```
