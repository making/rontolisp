# stream->list

`(stream->list stream)` `(stream->list stream k)`

`stream` の要素をリストにして返します。`k` を渡すと先頭の `k` 個だけを返すため、無限ストリームにも使えます。`k` がなければストリームは有限でなければなりません。R7RS ではなく *[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(stream->list (stream 1 2 3)) ; => (1 2 3)
(define (ints n) (cons-stream n (ints (+ n 1))))
(stream->list (ints 0) 4) ; => (0 1 2 3)
```
