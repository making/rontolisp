# write-sequence

`(write-sequence sequence stream &key start end)`

`sequence`（0 から 255 の整数からなる 1 次元配列）の要素をバイナリ出力ストリームに書き込み、そのシーケンスを返します。書き込みはインデックス `:start`（デフォルト 0）から始まり、インデックス `:end`（デフォルトは配列長）の手前で止まります。`:start`/`:end` キーワードはリテラルでなければなりませんが、その値は任意の式で構いません。3 つのバックエンドすべてで動作します。`write-byte` のループに展開されるため、`:direction :output :element-type '(unsigned-byte 8)` で開いたストリームが必要です。

ファイルシステムに触れるため、`write-sequence` はここでは実行可能な例ではなく静的に示します。

```console
(let ((buf (make-array 4)))
  (setf (aref buf 0) 222) (setf (aref buf 1) 173)
  (setf (aref buf 2) 190) (setf (aref buf 3) 239)
  (with-open-file (out "data.bin" :direction :output :element-type '(unsigned-byte 8))
    (write-sequence buf out)))  ; => the array
```

これは 4 バイト `DE AD BE EF` を `data.bin` に書き込みます。配列の一部だけを書き込むには `:start`/`:end` を使います。
