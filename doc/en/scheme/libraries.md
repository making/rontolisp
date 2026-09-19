# Libraries

The twelve R7RS libraries a file may `import`, and what each provides. Every name has its
own page in the [Reference](reference.md). A file that opens with `(import ...)` (see
[Syntax](syntax.md)) sees only the libraries it names; a file with none sees all twelve,
plus the [*Structure and Interpretation of Computer Programs* (SICP)-compatibility names](sicp.md).

| Library | Provides |
|---|---|
| [(scheme base)](reference/library-base.md) | The core: numbers, booleans, pairs and lists, symbols, characters, strings, vectors, bytevectors, control, exceptions, ports -- string and bytevector ports, the current ports -- and input and output; its syntax is on [Syntax](reference/syntax.md) |
| [(scheme write)](reference/library-write.md) | `display` and `write` |
| [(scheme read)](reference/library-read.md) | `read` |
| [(scheme char)](reference/library-char.md) | Unicode character classes, case mappings and case-insensitive comparisons |
| [(scheme inexact)](reference/library-inexact.md) | Transcendental functions and the float predicates |
| [(scheme cxr)](reference/library-cxr.md) | The three- and four-deep `car`/`cdr` compositions |
| [(scheme lazy)](reference/library-lazy.md) | Promises |
| [(scheme case-lambda)](reference/library-case-lambda.md) | `case-lambda` |
| [(scheme process-context)](reference/library-process-context.md) | `exit` and `emergency-exit` only |
| [(scheme eval)](reference/library-eval.md) | `eval` and `environment` -- see [eval](eval.md) |
| [(scheme repl)](reference/library-repl.md) | `interaction-environment` |
| [(scheme file)](reference/library-file.md) | File ports, `file-exists?` and `delete-file` |

Every input and output procedure takes an optional port argument and uses the current
port without one. The current ports are parameter objects, so `parameterize` redirects
them. A file port is opened on a file name -- see [(scheme file)](reference/library-file.md).

```scheme
(define out (open-output-string))
(parameterize ((current-output-port out))
  (display "captured ")
  (write '(1 "two")))
(write (get-output-string out)) (newline)

(define in (open-input-string "(a b) 42"))
(write (list (read in) (read in) (eof-object? (read in)))) (newline)
```

```
"captured (1 \"two\")"
((a b) 42 #t)
```

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

## Libraries of your own

A program splits into libraries with [define-library](reference/define-library.md): at the
beginning of the file, before its `import`s, or in a file of its own that
`(import (shapes circle))` finds as `shapes/circle.sld` beside the program. A library's
names are private unless it exports them. [include](reference/include.md) puts a file's
contents where it stands; both are read when the program is, so a compiled program needs
none of the files at run time.

```scheme
; file: shapes/circle.sld
(define-library (shapes circle)
  (export area)
  (import (scheme base))
  (begin
    (define pi 314/100)
    (define (area r) (* pi r r))))
```

```scheme
(import (scheme base) (scheme write) (shapes circle))
(define pi 3)
(write (list (area 10) pi))
(newline)
```

```
(314 3)
```
