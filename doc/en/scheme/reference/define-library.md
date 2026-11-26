# define-library

`(define-library (name...) declaration...)`

Program syntax, exported by no library: defines a library that a program, or another library, imports by its name. A name is a list of identifiers and exact non-negative integers; names beginning with `scheme` are reserved. The declarations are:

- `(export spec...)`, a spec being an identifier or `(rename internal external)`;
- `(import import-set...)`, what the library's body sees -- everything, as in a program with no `import`, when the library imports nothing;
- `(begin body...)`;
- `(include "file"...)` and `(include-ci "file"...)`, which add a file's contents to the body;
- `(include-library-declarations "file"...)`, whose file holds more declarations;
- `(cond-expand (requirement declaration...)...)`, the declarations of the clause [cond-expand](cond-expand.md) takes.

The body is lowered like a file of its own. Only the exported names reach an importer; every other top-level name stays private to the library, so a program may define the same name. A library is found among the `define-library` forms at the beginning of the importing file, before its `import`s, or as a file: `(import (geometry point))` reads `geometry/point.sld`, else `geometry/point.scm`, in the directory of the file the program started from. It runs once, when the first file imports it, however many files import it. A library may export a [define-syntax](define-syntax.md) macro: a name its template uses freely means what it meant in the library, a private one included, whatever the importer calls that name.

```scheme
(define-library (counter) (export next!) (import (scheme base)) (begin (define n 0) (define (next!) (set! n (+ n 1)) n)))
(import (scheme base) (scheme write) (counter))
(next!)
(display (next!))
(newline)
```

```
2
```

A library in its own file, with a private helper and an export under another name:

```scheme
; file: geometry/point.sld
(define-library (geometry point)
  (export make-point point-x point-y (rename add point-add))
  (import (scheme base))
  (begin
    (define-record-type point (make-point x y) point? (x point-x) (y point-y))
    (define (add a b)
      (make-point (+ (point-x a) (point-x b)) (+ (point-y a) (point-y b))))))
```

```scheme
(import (scheme base) (scheme write) (geometry point))
(define (add a b) 'mine)
(define p (point-add (make-point 1 2) (make-point 10 20)))
(write (list (point-x p) (point-y p) (add 1 2)))
(newline)
```

```
(11 22 mine)
```

A macro exported with the library's private procedure and variable, which the importer's own `count!` does not replace:

```scheme
; file: stack/macros.sld
(define-library (stack macros)
  (export push! pushes)
  (import (scheme base))
  (begin
    (define pushes 0)
    (define (count!) (set! pushes (+ pushes 1)))
    (define-syntax push!
      (syntax-rules ()
        ((_ x place) (begin (count!) (set! place (cons x place))))))))
```

```scheme
(import (scheme base) (scheme write) (stack macros))
(define (count!) 'mine)
(define items '())
(push! 1 items)
(push! 2 items)
(write (list items pushes (count!)))
(newline)
```

```
((2 1) 2 mine)
```
