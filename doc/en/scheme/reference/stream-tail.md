# stream-tail

`(stream-tail stream k)`

Returns the stream left after dropping the first `k` elements of `stream`, forcing each dropped cell. A *[Structure and Interpretation of Computer Programs](../sicp.md)* (SICP)/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(stream->list (stream-tail (stream 1 2 3) 1)) ; => (2 3)
(define (ints n) (cons-stream n (ints (+ n 1))))
(stream-head (stream-tail (ints 0) 5) 3) ; => (5 6 7)
```
