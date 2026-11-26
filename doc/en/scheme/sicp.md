# SICP Compatibility

*[Structure and Interpretation of Computer Programs](https://sarabander.github.io/sicp/html/index.xhtml)*
(SICP), by Harold Abelson and Gerald Jay Sussman, is the MIT textbook these names come
from, along with MIT Scheme, the dialect it was written for.

**Not R7RS.** MIT Scheme's `true`, `false` and `nil` (ordinary variables, not literals),
`user-initial-environment` and `system-global-environment` (both naming the one global
environment, see [eval](eval.md)), list and number utilities such as `filter`,
`fold-left` and `1+`, `runtime` and `random`, `parallel-execute` and `test-and-set!`,
and SICP's streams: `cons-stream` (syntax) and the stream procedures. Each has its own
page under [SICP Names](reference/library-sicp.md); R5RS's `scheme-report-environment`,
`exact->inexact` and `inexact->exact` are under
[(scheme r5rs)](reference/library-r5rs.md). A stream is `'()` or a pair whose cdr is a
promise, so `the-empty-stream` is `'()` and `stream-null?` is `null?`. These are visible
only when a program has no `(import ...)` at all -- like the six libraries -- and no
import names them, so an explicit import list leaves them unreachable.
[`--scheme-standard r7rs`](standards.md) hides them everywhere.

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
(define cells 0)
(define (count-by step n)
  (set! cells (+ cells 1))
  (cons-stream n (count-by step (+ n step))))
(define sevens (count-by 7 7))
(display (stream-head (stream-filter even? sevens) 5)) (newline)
(display (list (stream-ref sevens 9) cells)) (newline)
(define p (delay (begin (display "once ") 42)))
(display (list (force p) (force p))) (newline)
```

```
(14 28 42 56 70)
(70 10)
once (42 42)
```
