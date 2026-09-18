# stream-ref

`(stream-ref stream k)`

`stream` の `k` 番目（0 始まり）の要素を返します。それより前のセルは強制されます。R7RS ではなく *[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(stream-ref (stream 'a 'b 'c) 1) ; => b
(define (ints n) (cons-stream n (ints (+ n 1))))
(stream-ref (ints 0) 7) ; => 7
```
