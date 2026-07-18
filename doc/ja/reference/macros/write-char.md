# write-char

`(write-char character &optional stream)`

1 文字を標準出力(または指定したストリーム)に書き出し、その文字を返します。どのバックエンドでも 1 文字の文字列の [`write-string`](../functions/write-string.md) に展開されるため、ファイルストリームや文字列ストリームを含め、文字列出力が使える場所ならどこでも動きます。`format` と同様に関数値を持たないマクロとして分類されます。

```lisp
(write-char #\o)
(write-char #\k)
(terpri)
```

```
ok
```
