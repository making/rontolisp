# Libraries

Every procedure the front end knows, grouped by the R7RS library that exports it. A file
that opens with `(import ...)` (see [Syntax](syntax.md)) sees only the libraries it
names; a file with none sees all nine, plus the
[SICP-compatibility names](sicp.md).

## (scheme base)

| Category | Procedures |
|---|---|
| Equivalence | `eq?` `eqv?` `equal?` |
| Numbers | `+` `-` `*` `/` `=` `<` `>` `<=` `>=` `quotient` `remainder` `modulo` `floor-quotient` `floor-remainder` `truncate-quotient` `truncate-remainder` `abs` `min` `max` `gcd` `lcm` `expt` `square` `floor` `ceiling` `round` `truncate` `zero?` `positive?` `negative?` `odd?` `even?` `number?` `real?` `rational?` `integer?` `exact?` `inexact?` `exact-integer?` `exact` `inexact` `exact-integer-sqrt` `number->string` `string->number` |
| Booleans | `not` `boolean?` |
| Pairs and lists | `cons` `car` `cdr` `set-car!` `set-cdr!` `caar` `cadr` `cdar` `cddr` `list` `length` `append` `reverse` `list-tail` `list-ref` `list-copy` `memq` `memv` `member` `assq` `assv` `assoc` `null?` `pair?` `list?` |
| Symbols | `symbol?` `symbol->string` `string->symbol` |
| Characters | `char?` `char->integer` `integer->char` `char=?` `char<?` `char>?` `char<=?` `char>=?` |
| Strings | `string?` `make-string` `string` `string-length` `string-ref` `string-set!` `string=?` `string<?` `string>?` `string<=?` `string>=?` `substring` `string-append` `string-copy` `string->list` `list->string` |
| Vectors | `vector?` `make-vector` `vector` `vector-length` `vector-ref` `vector-set!` `vector->list` `list->vector` `vector-fill!` |
| Control | `procedure?` `apply` `map` `for-each` `call/cc` `call-with-current-continuation` `dynamic-wind` `values` `call-with-values` `error` |
| Output (current output port only) | `newline` `write-char` `write-string` |
| Input (current input port only, no port argument) | `read-char` `peek-char` `read-line` `char-ready?` `eof-object` `eof-object?` |

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

## (scheme write)

| Procedures |
|---|
| `display` `write` |

Both write to the current output port only, and both write a circular list or vector
with datum labels -- see [Printing](#printing) below.

## (scheme read)

| Procedures |
|---|
| `read` |

Reads from the current input port only, with no port argument.

## (scheme inexact)

| Procedures |
|---|
| `sqrt` `exp` `log` `sin` `cos` `tan` `asin` `acos` `atan` `finite?` `infinite?` `nan?` |

## (scheme cxr)

Every three- and four-deep `car`/`cdr` composition; `caar`/`cadr`/`cdar`/`cddr` are
`(scheme base)`. All 24 names are standard Common Lisp functions, so each forwards to
the one of the same name.

| Procedures |
|---|
| `caaar` `caadr` `cadar` `caddr` `cdaar` `cdadr` `cddar` `cdddr` |
| `caaaar` `caaadr` `caadar` `caaddr` `cadaar` `cadadr` `caddar` `cadddr` |
| `cdaaar` `cdaadr` `cdadar` `cdaddr` `cddaar` `cddadr` `cdddar` `cddddr` |

## (scheme lazy)

| Procedures |
|---|
| `force` `make-promise` `promise?` |

`delay` and `delay-force` are special forms (see [Syntax](syntax.md)), not procedures of
this library.

## (scheme process-context)

| Procedures |
|---|
| `exit` `emergency-exit` |

`#t` or no argument is status 0, `#f` is 1, an integer is its low eight bits. See
[Deviations](deviations.md) for how `exit` interacts with `dynamic-wind`.

## (scheme eval)

| Procedures |
|---|
| `eval` `environment` |

See [eval](eval.md) for the full semantics.

## (scheme repl)

| Procedures |
|---|
| `interaction-environment` |

See [eval](eval.md).

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
