# Libraries

The nine R7RS libraries a file may `import`, and what each provides. Every name has its
own page in the [Reference](reference.md). A file that opens with `(import ...)` (see
[Syntax](syntax.md)) sees only the libraries it names; a file with none sees all nine,
plus the [*Structure and Interpretation of Computer Programs* (SICP)-compatibility names](sicp.md).

| Library | Provides |
|---|---|
| [(scheme base)](reference/library-base.md) | The core: numbers, booleans, pairs and lists, symbols, characters, strings, vectors, control, exceptions, and input and output on the current ports; its syntax is on [Syntax](reference/syntax.md) |
| [(scheme write)](reference/library-write.md) | `display` and `write` |
| [(scheme read)](reference/library-read.md) | `read` |
| [(scheme inexact)](reference/library-inexact.md) | Transcendental functions and the float predicates |
| [(scheme cxr)](reference/library-cxr.md) | The three- and four-deep `car`/`cdr` compositions |
| [(scheme lazy)](reference/library-lazy.md) | Promises |
| [(scheme process-context)](reference/library-process-context.md) | `exit` and `emergency-exit` only |
| [(scheme eval)](reference/library-eval.md) | `eval` and `environment` -- see [eval](eval.md) |
| [(scheme repl)](reference/library-repl.md) | `interaction-environment` |

Input and output use the current ports only: no procedure takes a port argument.

```scheme
(define (sum-to n)
  (do ((i 0 (+ i 1)) (sum 0 (+ sum i))) ((> i n) sum)))
(display (sum-to 1000000)) (newline)

(define (first-even items)
  (call/cc (lambda (return)
    (for-each (lambda (x) (if (even? x) (return x))) items)
    #f)))
(display (first-even '(1 3 4 5))) (newline)

(call-with-values (lambda () (values 1 2)) (lambda (a b) (display (+ a b)) (newline)))
```

```
500000500000
4
3
```

## Printing

`write` and `display` write a circular list or vector with datum labels,
`#0=(a b c . #0#)`; structure shared without a cycle is written out each time.

An exact argument with an exact answer stays exact, and a flonum prints positionally
below `1e21` and with an exponent from there (and below `1e-6`):

```scheme
(write (list (sqrt 16) (sqrt 1/4) (sqrt 2) (exp 0) (atan 0 1))) (newline)
(write (list 123456789.123 1e21 0.000001 1.5e-7 (/ 1.0 0.0))) (newline)
```

```
(4 1/2 1.4142135623730951 1 0)
(123456789.123 1e21 0.000001 1.5e-7 +inf.0)
```
