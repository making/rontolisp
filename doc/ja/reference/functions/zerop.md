# zerop

`(zerop number)`

`number` がゼロであれば `t` を、そうでなければ `nil` を返します。任意の数値型に対して動作し、値がゼロの整数、浮動小数点数、有理数はいずれもこれを満たします。

```lisp
(zerop 0) ; => t
```

```lisp
(zerop 0.0) ; => t
```
