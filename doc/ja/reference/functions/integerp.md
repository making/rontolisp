# integerp

`(integerp object)`

`object` が整数なら `t` を、そうでなければ `nil` を返します。浮動小数点数や有理数は整数ではないため、`(integerp 3.0)` と `(integerp 1/2)` はどちらも `nil` です。3 つすべてのバックエンドで動作します。

```lisp
(integerp 42) ; => t
```

```lisp
(integerp 3.0) ; => nil
```
