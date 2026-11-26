# delay

`(delay expression)`

Answers a promise that evaluates `expression` the first time it is forced and remembers the value, so later forces answer it without evaluating again. A promise writes as `#<promise>`.

```scheme
(force (delay (* 6 7))) ; => 42
(define n 0)
(define p (delay (begin (set! n (+ n 1)) n)))
(list (force p) (force p)) ; => (1 1)
```
