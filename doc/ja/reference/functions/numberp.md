# numberp

`(numberp object)`

`object` が数値、すなわち整数、浮動小数点数、または有理数であれば `t` を、そうでなければ `nil` を返します。3 つのバックエンドすべてで動作します。

```lisp
(numberp 42) ; => T
```

```lisp
(numberp "42") ; => NIL
```
