# rontolisp:make-quantized-matrix

`(rontolisp:make-quantized-matrix format dims)`

`format`（`q8-0`）で次元 `dims`（階数 1 なら整数、または 1 個か 2 個の整数のリスト。
最後は 32 の倍数）の、全要素ゼロの量子化行列（[`rontolisp:quantize`](rontolisp-quantize.md)）
を作ります。用途は `read-sequence` の宛先です。量子化行列の記憶域はそのまま ggml の
ブロックレイアウト（32 要素につき 34 バイト）なので、GGUF ファイル内の Q8_0 テンソルは
1 回のバルク転送で入り、`write-sequence` は同じバイト列を書き戻します。
[`gguf:read`](gguf-read.md) が Q8_0 テンソルを読み込む方法がこれです。

```lisp
(let ((m (rontolisp:make-quantized-matrix 'q8-0 '(2 64))))
  (list m (array-total-size m) (aref m 1 63) (array-element-type m)))
; => (#<quantized-matrix q8-0 (2 64)> 128 0.0 Q8-0)
```

このバッファに対する `read-sequence` と `write-sequence`（`:start` / `:end` も）は
**バイト**を数えます。ブロックあたり 34 バイトで、`(rows cols)` の行列の全転送は
`rows * cols / 32 * 34` バイトです。

インタプリタと JVM のみ。WASM バックエンドでは呼び出し時にエラーを通知するので、
リーダの Q8_0 分岐はどこでもコンパイルでき、到達したときだけ拒否されます。
