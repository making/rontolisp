# write-byte

`(write-byte byte stream)`

1 バイト（0 から 255 の整数）をバイナリ出力ストリーム（`:direction :output :element-type '(unsigned-byte 8)` で開いたストリーム）に書き込み、そのバイトを返します。3 つのバックエンドすべてで動作します。バイトは生のまま書き込まれ、改行などのフレーミングは付加されません。

ファイルシステムに触れるため、`write-byte` はここでは実行可能な例ではなく静的に示します。

```console
(with-open-file (out "data.bin" :direction :output :element-type '(unsigned-byte 8))
  (write-byte 137 out)  ; => 137
  (write-byte 80 out)
  (write-byte 78 out)
  (write-byte 71 out))
```

これは 4 バイト `89 50 4E 47`（PNG シグネチャの先頭）を `data.bin` に書き込みます。インタプリタと JVM では 0-255 の範囲外の値はエラーになります。
