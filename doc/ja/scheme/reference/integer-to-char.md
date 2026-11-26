# integer->char

`(integer->char n)`

Unicode コードポイントが `n` である文字を返します。`char->integer` の逆です。

```scheme
(integer->char 97) ; => #\a
(integer->char 955) ; => #\λ
```
