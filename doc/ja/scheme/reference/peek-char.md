# peek-char

`(peek-char)`

現在の入力ポートの次の文字を、消費せずに返します。したがって続く `read-char` は同じ文字を返します。入力の終わりではファイル終端オブジェクトを返します。ポート引数はありません。

```stdin
xy
```

```scheme
(write (peek-char))
(write (peek-char))
(write (read-char))
(write (read-char))
(newline)
```

```
#\x#\x#\x#\y
```
