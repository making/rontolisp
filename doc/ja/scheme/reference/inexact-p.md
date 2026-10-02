# inexact?

`(inexact? z)`

数値 `z` が不正確、つまり浮動小数点数か、いずれかの部が浮動小数点数の複素数なら `#t` を返します。

```scheme
(inexact? 1.0) ; => #t
(inexact? 1) ; => #f
```
