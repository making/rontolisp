# stream-ref

`(stream-ref stream k)`

Returns element `k` (zero-based) of `stream`, forcing the cells before it. A *[Structure and Interpretation of Computer Programs](../sicp.md)* (SICP)/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(stream-ref (stream 'a 'b 'c) 1) ; => b
(define (ints n) (cons-stream n (ints (+ n 1))))
(stream-ref (ints 0) 7) ; => 7
```
