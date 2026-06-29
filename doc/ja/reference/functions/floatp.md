# floatp

`(floatp object)`

`object` が浮動小数点数なら `t` を、そうでなければ `nil` を返します。整数や有理数は浮動小数点数ではないため、`(floatp 3)` と `(floatp 1/2)` はどちらも `nil` です。3 つすべてのバックエンドで動作します。

```lisp
(floatp 3.14) ; => t
```

```lisp
(floatp 3) ; => nil
```
