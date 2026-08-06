# write-string

`(write-string string &optional stream &key start end)`

文字列の生の内容を、周囲のクォートも末尾の改行も付けずに書き込み、その文字列を返します（改行なしの `write-line`）。stream 引数がない場合は標準出力に書き込みます。出力ストリーム（ファイルストリームまたは `with-output-to-string` の文字列ストリーム）を指定すると、そちらに書き込みます。`:start`/`:end` キーワードは書き込む部分文字列を区切ります（`nil` の `:end` は文字列長の意味）。戻り値は常に元の文字列全体です。rontolisp の Gray 出力ストリーム基底クラスを継承した CLOS インスタンスもストリームとして使え、書き込みは `rontolisp:stream-write-string` にディスパッチされます。[TCP/TLS ソケットハンドル](../../guides/tcp-sockets.md)も使え、その場合は文字列の UTF-8 バイトが末尾の改行なしで即座にワイヤーへ送出されます。

```lisp
(write-string "one, ")
(write-string "two")
```

```
one, two
```
