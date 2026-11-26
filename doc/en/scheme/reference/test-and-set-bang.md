# test-and-set!

`(test-and-set! cell)`

Atomically tests and sets the car of the pair `cell`: when it is `#f`, sets it to `#t` and returns `#f`; otherwise leaves it alone and returns `#t`. The test and the set cannot be interleaved with another thread's, on every backend -- the primitive *[Structure and Interpretation of Computer Programs](../sicp.md)* (SICP) builds a mutex on. A SICP/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(test-and-set! (list #f)) ; => #f
(define cell (list #f))
(test-and-set! cell) ; => #f
(test-and-set! cell) ; => #t
cell ; => (#t)
```
