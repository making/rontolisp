# Scheme (experimental)

**Experimental.** rontolisp reads a subset of R7RS-small -- `(scheme base)` and
`(scheme write)` -- just large enough to run a Scheme program on every backend.
Conformance is partial by design and nothing here is a compatibility promise. Use it to
try a Scheme program on the JVM or WebAssembly; write Common Lisp for anything you need
to keep working.

A `.scm` file is read as Scheme; `--source-language scheme` says so for any other file.
The language is picked per file, so one program may mix the two.

```bash
rontolisp hello.scm                                # interpreter
rontolisp hello.scm -o Hello.class && java Hello   # JVM
rontolisp hello.scm -o hello.wasm && wasmtime run hello.wasm
rontolisp hello.scm -o hello-c.wasm --component && wasmtime run hello-c.wasm
rontolisp prog.txt --source-language scheme        # any extension
```

`--no-gc` is refused: that backend has no pairs, symbols or closures.

```scheme
(import (scheme base) (scheme write))

(define (count-up n)
  (let loop ((i 0) (acc '()))
    (if (= i n)
        (reverse acc)
        (loop (+ i 1) (cons (* i i) acc)))))

(define-record-type point (make-point x y) point? (x point-x) (y point-y))

(display (count-up 5)) (newline)
(write (list (point-x (make-point 3 4)) (if '() 'true 'false) #f "s")) (newline)
```

```
(0 1 4 9 16)
(3 true #f "s")
```

## REPL

With no file, `--source-language scheme` starts a Scheme REPL. Values are echoed as
`write` prints them; a definition, a `set!` and a procedure called for its effect
(`display`) echo nothing. A form may span lines. An error, a stack overflow included, is
reported and the session goes on with its definitions.

```console
$ rontolisp --source-language scheme
scheme> (define (square x) (* x x))
scheme> (map square '(1 2 3))
(1 4 9)
scheme> (set! square -)
scheme> (square 5)
-5
scheme> (list #t #f '() 'Sym)
(#t #f () Sym)
scheme> (quit)
```

Everything `(scheme base)` and `(scheme write)` export is visible from the start, and an
`(import ...)` typed at the prompt only adds names. Definitions typed at separate prompts
see each other in either order, as they would in one file. Two things differ from a file,
because a form is fixed when it is typed: redefining a built-in procedure (`square`) does
not reach the forms typed before it, and a procedure that calls itself in tail position
keeps looping on itself if a later `set!` replaces it while an old copy is still held.

## What is supported

- **Reader** (case-sensitive): `#t` `#f` `#true` `#false`, integers, decimals, rationals,
  `#x` `#b` `#o` `#d`, `#\a` `#\space` `#\newline` `#\x41`, strings with
  `\n \t \" \\ \xHH;`, `#( )` vectors, dotted pairs, `'` `` ` `` `,` `,@`, `;`, `#;`,
  `#| |#`.
- **Syntax**: `define` (both forms, internal definitions as `letrec*`), `define-values`,
  `lambda`, `if`, `cond` (`else`, `=>`), `case`, `and`, `or`, `when`, `unless`, `let`,
  `let*`, `letrec`, `letrec*`, named `let`, `do`, `begin`, `set!`, `quote`, `quasiquote`,
  `let-values`, `let*-values`, `define-record-type` (top level only), and
  `(import (scheme base) (scheme write))` with `only` / `except` / `prefix` / `rename`.
- **Procedures**: `eq? eqv? equal?`; `+ - * / = < > <= >= quotient remainder modulo
  floor-quotient floor-remainder truncate-quotient truncate-remainder abs min max gcd lcm
  expt square floor ceiling round truncate zero? positive? negative? odd? even? number?
  real? rational? integer? exact? inexact? exact-integer? exact inexact number->string
  string->number`; `not boolean?`; `cons car cdr set-car! set-cdr! caar cadr cdar cddr list
  length append reverse list-tail list-ref list-copy memq memv member assq assv assoc null?
  pair? list?`; `symbol? symbol->string string->symbol`; `char? char->integer integer->char
  char=? char<? char>? char<=? char>=?`; `string? make-string string string-length
  string-ref string-set! string=? string<? string>? string<=? string>=? substring
  string-append string-copy string->list list->string`; `vector? make-vector vector
  vector-length vector-ref vector-set! vector->list list->vector vector-fill!`;
  `procedure? apply map for-each call/cc call-with-current-continuation dynamic-wind
  values call-with-values error`; `display write newline write-char write-string` (the
  current output port only). `write` and `display` write a circular list or vector with
  datum labels, `#0=(a b c . #0#)`; structure shared without a cycle is written out each
  time.

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

## Deviations

- **Tail calls are proper only where they become a loop**: a named `let` or `do`, and a
  procedure calling itself in tail position. Mutual and higher-order tail calls use
  stack: a pair of procedures calling each other overflows the JVM's default stack
  between 2,000 and 5,000 calls deep, and the interpreter and WebAssembly between 10,000
  and 100,000.
- **`call/cc` is escape-only.** A continuation can be called while its `call/cc` is still
  running, once. There is no re-entry, so no generators or coroutines through it, and
  `dynamic-wind` runs its `before` exactly once.
- `call-with-values` is a direct binding when both arguments are written as `lambda`
  expressions; any other shape goes through a list.
- An uncaught `error` ends the program with its message and irritants. There is no
  `guard` to catch it.
- A record prints in Common Lisp's `#S(...)` syntax. `equal?` compares records by
  identity.
- `write` prints `'x` as `(quote x)`.
- Error messages spell Common Lisp names (`CAR`).
- **Not yet**: `define-syntax` / `syntax-rules`, `define-library`, `guard` / `raise`,
  `parameterize`, `case-lambda`, `delay`, bytevectors, ports other than the current
  output port, `eval`, `(scheme char)` and the other libraries, `|...|` identifiers,
  `+inf.0` / `+nan.0`. The syntactic ones are refused by name when the file is read.

## Mixing with Common Lisp

Scheme identifiers keep their case, so a Common Lisp file calls a Scheme procedure by its
escaped name. A top-level procedure that is defined once and never `set!` is an ordinary
function.

```console
$ cat lib.scm
(define (twice x) (* 2 x))
$ cat main.lisp
(load "lib.scm")
(print (|twice| 21))
$ rontolisp main.lisp
42
```

In that direction only three values need care: `'()` is `NIL`, `#t` is `T`, and `#f` is
its own value -- a `#f` handed to Common Lisp code is TRUE there, and a `NIL` returned to
Scheme is the empty list, which is true.
