# torch:log

`(torch:log a)`

微分可能な要素ごとの自然対数 (`linalg:log`) です。勾配は `g / x` です。

```lisp
(torch:data (torch:log (torch:tensor '(1.0 2.718281828459045)))) ; => #f(0.0 0.99999994)
```
