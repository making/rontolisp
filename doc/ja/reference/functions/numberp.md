# numberp

`(numberp object)`

`object` が数値、すなわち整数、浮動小数点数、有理数、または複素数であれば `t` を、そうでなければ `nil` を返します。3 つのバックエンドすべてで動作します。

```lisp
(numberp 42) ; => T
```

```lisp
(numberp #c(1 2)) ; => T
```

```lisp
(numberp "42") ; => NIL
```
