# stream-map

`(stream-map proc stream1 stream ...)`

`proc` を各ストリームの要素ごとに適用したストリームを返します。計算は遅延されるため無限ストリームにも使えます。複数のストリームを渡すと、最も短いものが終わったところで終わります。R7RS ではなく *[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(stream->list (stream-map (lambda (x) (* x x)) (stream 1 2 3))) ; => (1 4 9)
(define (ints n) (cons-stream n (ints (+ n 1))))
(stream-head (stream-map + (ints 0) (ints 100)) 3) ; => (100 102 104)
```
