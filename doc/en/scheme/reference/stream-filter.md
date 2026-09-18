# stream-filter

`(stream-filter pred stream)`

Returns the stream of the elements of `stream` for which `pred` returns a true value, computed lazily, so it works on infinite streams. A SICP/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(stream->list (stream-filter odd? (stream 1 2 3 4 5))) ; => (1 3 5)
(define (ints n) (cons-stream n (ints (+ n 1))))
(stream-head (stream-filter even? (ints 1)) 3) ; => (2 4 6)
```
