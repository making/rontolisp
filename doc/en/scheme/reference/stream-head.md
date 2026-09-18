# stream-head

`(stream-head stream k)`

Returns the first `k` elements of `stream` as a list. A stream shorter than `k` answers all of its elements rather than an error. A SICP/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(stream-head (stream 1 2 3) 2) ; => (1 2)
(define (ints n) (cons-stream n (ints (+ n 1))))
(stream-head (ints 10) 3) ; => (10 11 12)
(stream-head (stream 1 2) 5) ; => (1 2)
```
