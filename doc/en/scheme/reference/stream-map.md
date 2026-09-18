# stream-map

`(stream-map proc stream1 stream ...)`

Returns the stream of `proc` applied element-wise to the given streams, computed lazily, so it works on infinite streams. With several streams it ends when the shortest one does. A SICP/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(stream->list (stream-map (lambda (x) (* x x)) (stream 1 2 3))) ; => (1 4 9)
(define (ints n) (cons-stream n (ints (+ n 1))))
(stream-head (stream-map + (ints 0) (ints 100)) 3) ; => (100 102 104)
```
