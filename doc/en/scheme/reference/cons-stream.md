# cons-stream

`(cons-stream a b)`

Syntax, not a procedure: builds a stream whose first element is the value of `a` and whose rest is `b`, which is not evaluated until the stream's cdr is first asked for, and then only once. A stream is `'()` or a pair whose cdr is a promise, so infinite streams are ordinary. A *[Structure and Interpretation of Computer Programs](../sicp.md)* (SICP)/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(stream-car (cons-stream 1 (/ 1 0))) ; => 1
(define (ints n) (cons-stream n (ints (+ n 1))))
(stream-head (ints 0) 5) ; => (0 1 2 3 4)
```
