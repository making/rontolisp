# stream-tail

`(stream-tail stream k)`

`stream` の先頭 `k` 個を取り除いた残りのストリームを返します。取り除く各セルは強制されます。R7RS ではなく *[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(stream->list (stream-tail (stream 1 2 3) 1)) ; => (2 3)
(define (ints n) (cons-stream n (ints (+ n 1))))
(stream-head (stream-tail (ints 0) 5) 3) ; => (5 6 7)
```
