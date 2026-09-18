# parallel-execute

`(parallel-execute thunk ...)`

Runs each thunk (a procedure of no arguments) concurrently and returns once every one has finished; an error in a thunk is signaled then. On the interpreter and the JVM each thunk gets its own thread. WebAssembly has no threads, so there the thunks run one after another in argument order -- one of the interleavings a threaded run may produce. A *[Structure and Interpretation of Computer Programs](../sicp.md)* (SICP)/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(define total 0)
(define cell (list false))
(define (add! n)
  (lambda ()
    (let wait () (if (test-and-set! cell) (wait)))
    (set! total (+ total n))
    (set-car! cell false)))
(parallel-execute (add! 1) (add! 10) (add! 100))
(display total)
(newline)
```

```
111
```
