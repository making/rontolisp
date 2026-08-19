# torch:exp

`(torch:exp a)`

微分可能な要素ごとの `e^x` (`linalg:exp`) です。backward は順伝播の結果を再利用します (`d/dx e^x = e^x`)。

```lisp
(torch:data (torch:exp (torch:tensor '(0.0 1.0)))) ; => #d(1.0 2.718281828459045)
```
