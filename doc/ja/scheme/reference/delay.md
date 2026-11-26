# delay

`(delay expression)`

最初に force されたときに `expression` を評価してその値を記憶するプロミスを返します。以後の force は再評価せずにその値を返します。プロミスは `#<promise>` と書き出されます。

```scheme
(force (delay (* 6 7))) ; => 42
(define n 0)
(define p (delay (begin (set! n (+ n 1)) n)))
(list (force p) (force p)) ; => (1 1)
```
