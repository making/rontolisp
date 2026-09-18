# SICP Compatibility

**Not R7RS.** MIT Scheme's `true`, `false` and `nil` (ordinary variables, not literals),
`user-initial-environment` and `system-global-environment` (both naming the one global
environment, see [eval](eval.md)), list and number utilities such as `filter`,
`fold-left` and `1+`, `runtime` and `random`, `parallel-execute` and `test-and-set!`,
and SICP's streams: `cons-stream` (syntax) and the stream procedures. Each has its own
page under [SICP Names](reference/library-sicp.md); R5RS's `scheme-report-environment`
is under [(scheme r5rs)](reference/library-r5rs.md). A stream is `'()` or a pair whose
cdr is a promise, so `the-empty-stream` is `'()` and `stream-null?` is `null?`. These
are visible only when a program has no `(import ...)` at all -- like the six libraries
-- and no import names them, so an explicit import list leaves them unreachable.

```scheme
(display (list true false nil (cadddr '(1 2 3 4)))) (newline)
(display (filter odd? '(1 2 3 4 5))) (newline)
(display (fold-left cons '() '(1 2 3))) (newline)
```

```
(#t #f () 4)
(1 3 5)
(((() . 1) . 2) . 3)
```

`parallel-execute` runs each thunk in its own thread on the interpreter and the JVM and
returns once every one has finished; an error in a thunk is signaled then. WebAssembly has
no threads, so there the thunks run one after another in argument order -- one of the
interleavings a threaded run may produce, in which a serializer built on `test-and-set!`
never has to wait. `test-and-set!` is atomic on every backend.

```scheme
(define cell (list false))
(display (list (test-and-set! cell) (test-and-set! cell))) (newline)
(define finished '())
(define (finish name) (lambda () (set! finished (cons name finished))))
(parallel-execute (finish 'only))
(display finished) (newline)
```

```
(#f #t)
(only)
```

A promise is forced once and remembers its value; walking a stream forces each cell once.

```scheme
(define (integers-from n) (cons-stream n (integers-from (+ n 1))))
(define (sieve s)
  (cons-stream (stream-car s)
               (sieve (stream-filter (lambda (x) (not (= 0 (remainder x (stream-car s)))))
                                     (stream-cdr s)))))
(display (stream-head (sieve (integers-from 2)) 10)) (newline)
(define p (delay (begin (display "once ") 42)))
(display (list (force p) (force p))) (newline)
```

```
(2 3 5 7 11 13 17 19 23 29)
once (42 42)
```
