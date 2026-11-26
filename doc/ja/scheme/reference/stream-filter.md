# stream-filter

`(stream-filter pred stream)`

`stream` の要素のうち `pred` が真の値を返すもののストリームを返します。計算は遅延されるため無限ストリームにも使えます。R7RS ではなく *[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(stream->list (stream-filter odd? (stream 1 2 3 4 5))) ; => (1 3 5)
(define (ints n) (cons-stream n (ints (+ n 1))))
(stream-head (stream-filter even? (ints 1)) 3) ; => (2 4 6)
```
