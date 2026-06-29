# numerator

`(numerator rational)`

有理数を既約形にしたときの分子を返します。整数の場合は自身が分子となるため、整数をそのまま返します。

```lisp
(numerator 3/4) ; => 3
```

```lisp
(numerator 5) ; => 5
```
