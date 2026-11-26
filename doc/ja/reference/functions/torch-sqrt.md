# torch:sqrt

`(torch:sqrt a)`

微分可能な要素ごとの平方根 (`linalg:sqrt`) です。勾配は `g / (2 sqrt x)` です。

```lisp
(torch:data (torch:sqrt (torch:tensor '(4.0 9.0)))) ; => #f(2.0 3.0)
```
