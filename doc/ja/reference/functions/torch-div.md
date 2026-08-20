# torch:div

`(torch:div a b)`

numpy スタイルのブロードキャストを伴う、微分可能な要素ごとの `a / b` (`linalg:div`) です。分子の勾配は `g / b`、分母の勾配は `-g * a / b^2` です。

```lisp
(torch:data (torch:div (torch:tensor '(6.0 9.0)) (torch:tensor '(2.0 3.0)))) ; => #f(3.0 3.0)
```
