# stream->list

`(stream->list stream)` `(stream->list stream k)`

Returns the elements of `stream` as a list. With `k`, only the first `k` elements, which makes it usable on an infinite stream; without it, the stream must be finite. A *[Structure and Interpretation of Computer Programs](../sicp.md)* (SICP)/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(stream->list (stream 1 2 3)) ; => (1 2 3)
(define (ints n) (cons-stream n (ints (+ n 1))))
(stream->list (ints 0) 4) ; => (0 1 2 3)
```
