# read-byte

`(read-byte stream &optional eof-error-p eof-value)`

バイナリ入力ストリーム（`:element-type '(unsigned-byte 8)` で開いたストリーム）から 1 バイトを読み込み、0 から 255 の整数として返します。ファイル終端ではデフォルトでエラーを通知します。`eof-error-p` に `nil` を渡すと、代わりに `eof-value`（デフォルト `nil`）を返します。3 つのバックエンドすべてで動作します。バイトは生のまま通過し、0（NUL）、10（LF）、34（`"`）といった値も解釈されません。

ファイルシステムに触れるため、`read-byte` はここでは実行可能な例ではなく静的に示します。

```console
(with-open-file (in "data.bin" :element-type '(unsigned-byte 8))
  (read-byte in)         ; => 137
  (read-byte in nil nil)) ; => nil at end of file
```

最初の呼び出しは `data.bin` の次のバイトを返します。2 番目の形式はエラーを通知せずにファイル終端まで読み進め、バイトが尽きると `nil` を返します。ファイル全体を読むときの一般的なループ終了判定です。
