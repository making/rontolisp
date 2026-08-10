# read-byte

`(read-byte stream &optional eof-error-p eof-value)`

バイナリ入力ストリーム（`:element-type '(unsigned-byte 8)` で開いたストリーム）から 1 バイトを読み込み、0 から 255 の整数として返します。ファイル終端ではデフォルトで `end-of-file` コンディションを通知します（`end-of-file` としても `error` としても捕捉できます）。`eof-error-p` に `nil` を渡すと、代わりに `eof-value`（デフォルト `nil`）を返します。4 つのバックエンドすべてで動作します。バイトは生のまま通過し、0（NUL）、10（LF）、34（`"`）といった値も解釈されません。

`stream` は他のストリーム操作と同じ指定子を受け取ります。`t` はプロセスの標準入力、`nil` は現在の `*standard-input*`（束縛しない限り `t` を保持します）を表します。したがって `(read-byte *standard-input*)` は標準入力から生のオクテットを読み込みます。バイト指向のフィルタが入力を読む方法です。

ファイルシステムに触れるため、`read-byte` はここでは実行可能な例ではなく静的に示します。

```console
(with-open-file (in "data.bin" :element-type '(unsigned-byte 8))
  (read-byte in)         ; => 137
  (read-byte in nil nil)) ; => nil at end of file

(read-byte *standard-input* nil nil) ; => the next octet of stdin, nil at EOF
```

最初の呼び出しは `data.bin` の次のバイトを返します。2 番目の形式はエラーを通知せずにファイル終端まで読み進め、バイトが尽きると `nil` を返します。ファイル全体を読むときの一般的なループ終了判定です。

同じストリームで `read-byte` と `read-line` / `read-char` を混在させないでください。文字読み込みは先読みバッファを持つため、後続の `read-byte` が返すはずのバイトが既に消費されている可能性があります。
