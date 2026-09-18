# Scheme (experimental)

**Experimental.** rontolisp reads a subset of R7RS-small -- `(scheme base)`,
`(scheme write)`, `(scheme read)`, `(scheme inexact)`, `(scheme cxr)`, `(scheme lazy)`,
`(scheme process-context)`'s `exit`, `(scheme eval)` and `(scheme repl)` -- just large
enough to run a
Scheme program on every backend. Conformance is partial by design and nothing here is a
compatibility promise. Use it to try a Scheme program on the JVM or WebAssembly; write
Common Lisp for anything you need to keep working.

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
`write` prints them. A definition echoes nothing, and neither does the unspecified value
-- what `display`, `set!`, `for-each` or an `if` with no branch taken answer, also when a
procedure of your own ends in one. A form may span lines. An error, a stack overflow
included, is reported and the session goes on with its definitions; `(exit)` ends it.
With input piped in, the session is a script runner, as the
[Common Lisp REPL](../getting-started/repl.md) is: no prompt, errors on standard error,
exit status 1 if any form failed.

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
scheme> (define (show x) (display x) (newline))
scheme> (show 'done)
done
scheme> (exit)
```

Everything those nine libraries export -- plus the SICP-compatibility names below, which
no `(import ...)` names -- is visible from the start, and an
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
  `let-values`, `let*-values`, `define-record-type` (top level only), `delay`,
  `delay-force`, and
  `(import (scheme base) (scheme write) (scheme read) (scheme inexact) (scheme cxr) (scheme lazy)
  (scheme process-context) (scheme eval) (scheme repl))` with `only` / `except` /
  `prefix` / `rename`.
- **Procedures**: `eq? eqv? equal?`; `+ - * / = < > <= >= quotient remainder modulo
  floor-quotient floor-remainder truncate-quotient truncate-remainder abs min max gcd lcm
  expt square floor ceiling round truncate zero? positive? negative? odd? even? number?
  real? rational? integer? exact? inexact? exact-integer? exact inexact exact-integer-sqrt
  number->string string->number`; `(scheme inexact)`: `sqrt exp log sin cos tan asin acos
  atan finite? infinite? nan?`; `(scheme lazy)`: `force make-promise promise?`; `not boolean?`; `cons car cdr set-car! set-cdr! caar cadr
  cdar cddr list length append reverse list-tail list-ref list-copy memq memv member assq
  assv assoc null? pair? list?`; `symbol? symbol->string string->symbol`; `char? char->integer integer->char
  char=? char<? char>? char<=? char>=?`; `string? make-string string string-length
  string-ref string-set! string=? string<? string>? string<=? string>=? substring
  string-append string-copy string->list list->string`; `vector? make-vector vector
  vector-length vector-ref vector-set! vector->list list->vector vector-fill!`;
  `procedure? apply map for-each call/cc call-with-current-continuation dynamic-wind
   values call-with-values error`; `display write newline write-char write-string` (the
   current output port only); `read eof-object eof-object? read-char peek-char read-line
   char-ready?` (the current input port only, no port argument); `exit emergency-exit` (`#t` or no argument is status 0, `#f`
  is 1, an integer is its low eight bits); `(scheme eval)`: `eval environment`;
  `(scheme repl)`: `interaction-environment`. `write` and `display` write a circular list
  or vector with datum labels, `#0=(a b c . #0#)`; structure shared without a cycle is
  written out each time.
- **SICP compatibility, not R7RS**: `true false nil` (ordinary variables, not literals);
  `user-initial-environment system-global-environment` and R5RS's
  `scheme-report-environment`, all naming the one global environment (see `eval` below);
  `filter reduce fold-left fold-right delete last-pair append! list-index 1+ -1+ random
  runtime parallel-execute test-and-set!`; streams: `cons-stream` (syntax),
  `the-empty-stream stream-car stream-cdr
  stream-first stream-rest stream-pair? stream-null? empty-stream? stream list->stream
  stream->list stream-head stream-tail stream-ref stream-map stream-for-each stream-filter
  stream-append`. A stream is `'()` or a pair whose cdr is a promise, so
  `the-empty-stream` is `'()` and `stream-null?` is `null?`. These are visible only when
  a program has no `(import ...)` at all -- like the six libraries -- and no import names
  them, so an explicit import list leaves them unreachable.

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

## eval

`(eval datum env)` evaluates a datum at run time, on every backend. Every environment
specifier is the one global environment: `(interaction-environment)`,
`(scheme-report-environment 5)`, `(environment '(scheme base) ...)` -- its import sets
are checked against the libraries above -- and MIT Scheme's `user-initial-environment`
and `system-global-environment` all name it, and the argument may be left out. It holds
the program's variables (including what `eval` itself defined), the program's
procedures, and the built-in procedures, in that order. A `define` inside `eval` is a program global, visible to later `eval`s
and to the program itself (through `eval` -- a name first defined this way has no
compiled direct reference), and a `set!` of one of the program's variables assigns
it: the program reads what `eval` wrote.

```scheme
(define (execute exp) (apply (eval (car exp) user-initial-environment) (cdr exp)))
(display (execute '(> 5 3))) (newline)
(eval '(define (fact n) (if (= n 0) 1 (* n (fact (- n 1))))) (interaction-environment))
(display (list (eval '(fact 10) (interaction-environment))
               (eval '(let loop ((i 0)) (if (= i 100000) i (loop (+ i 1))))
                     (interaction-environment))))
(newline)
```

```
#t
(3628800 100000)
```

Inside `eval`, a named `let`, a `do` and a procedure calling itself run in constant
stack; every other call uses it. `define-record-type`, `define-values`, `let-values`,
`import` and the syntax the reader refuses are refused by name inside `eval` too. A
compiled program's `eval` resolves a built-in procedure only when the program spells its
name somewhere -- as a symbol, quoted data included, or inside a string -- while the
interpreter resolves them all.

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
- A first-class `values` -- `(apply values '(1 2))`, `values` reached through a variable,
  `values` inside `eval` -- answers its first value only on the compiled backends; the
  interpreter answers them all. Written as a call, `(values 1 2)`, it answers them all
  everywhere.
- An uncaught `error` ends the program with its message and irritants. There is no
  `guard` to catch it.
- A record prints in Common Lisp's `#S(...)` syntax. `equal?` compares records by
  identity.
- `write` prints `'x` as `(quote x)`, and the unspecified value as `#!unspecific`. It is
  one object, true in a test.
- `exit` runs the `after` thunks of the `dynamic-wind`s it is inside, then ends the
  process with its status; only `emergency-exit` ends the process where it stands.
- There are no complex numbers: `(sqrt -4)`, `(log -1)` and `(asin 2)` end the program
  with an error naming the procedure.
- Error messages spell Common Lisp names (`CAR`).
- **Not yet**: `define-syntax` / `syntax-rules`, `define-library`, `guard` / `raise`,
  `parameterize`, `case-lambda`, bytevectors, ports other than the current
  output and input ports (string ports, and a port argument to `read` / `write` /
  `display`), `(scheme char)` and the other libraries, `|...|` identifiers,
  reading `+inf.0` / `+nan.0`. The syntactic ones are refused by name when the file is read.

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
