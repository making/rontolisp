# rationalp

`(rationalp object)`

`object` が有理数（整数または比）であれば `t` を、そうでなければ `nil` を返します。浮動小数点数は有理数ではないため、`(rationalp 3.14)` は `nil` です。3 つすべてのバックエンドで動作します。

```lisp
(rationalp 1/2) ; => t
```

```lisp
(rationalp 3.14) ; => nil
```
