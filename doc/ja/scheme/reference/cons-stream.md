# cons-stream

`(cons-stream a b)`

手続きではなく構文です。先頭要素が `a` の値で、残りが `b` であるストリームを作ります。`b` はストリームの cdr が初めて求められるまで評価されず、評価も 1 回だけです。ストリームは `'()` か、cdr がプロミスであるペアなので、無限ストリームも普通に書けます。R7RS ではなく SICP/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(stream-car (cons-stream 1 (/ 1 0))) ; => 1
(define (ints n) (cons-stream n (ints (+ n 1))))
(stream-head (ints 0) 5) ; => (0 1 2 3 4)
```
