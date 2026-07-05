# write-string

`(write-string string &optional stream)`

文字列の生の内容を、周囲のクォートも末尾の改行も付けずに書き込み、その文字列を返します（改行なしの `write-line`）。stream 引数がない場合は標準出力に書き込みます。出力ストリーム（ファイルストリームまたは `with-output-to-string` の文字列ストリーム）を指定すると、そちらに書き込みます。

```lisp
(write-string "one, ")
(write-string "two")
```

```
one, two
```
