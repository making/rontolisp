# stream-head

`(stream-head stream k)`

`stream` の先頭 `k` 個の要素をリストにして返します。`k` より短いストリームでは、エラーにならず全要素を返します。R7RS ではなく *[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(stream-head (stream 1 2 3) 2) ; => (1 2)
(define (ints n) (cons-stream n (ints (+ n 1))))
(stream-head (ints 10) 3) ; => (10 11 12)
(stream-head (stream 1 2) 5) ; => (1 2)
```
