# write-char

`(write-char character &optional stream)`

1 文字を標準出力(または指定したストリーム)に書き出し、その文字を返します。どのバックエンドでも 1 文字の文字列を [`write-string`](write-string.md) で書き出すため、ファイルストリーム、文字列ストリーム、[ソケットハンドル](../../guides/tcp-sockets.md)を含め、文字列出力が使える場所ならどこでも動きます。[Gray ストリーム](../../guides/gray-streams.md)のインスタンスには `rontolisp:stream-write-char` を呼びます。関数なので、`#'write-char` を `funcall`、`apply`、`mapc` に渡せます。

```lisp
(write-char #\o)
(write-char #\k)
(terpri)
```

```
ok
```
