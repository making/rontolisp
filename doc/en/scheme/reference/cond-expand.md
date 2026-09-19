# cond-expand

`(cond-expand (requirement body...)... [(else body...)])`

Takes the first clause whose requirement holds, else the `else` clause, and stands for its body as a `begin` would: at the top level and in a body its definitions are definitions there, around the leading `import`s its imports are the program's, and in an expression it is the value of the last form. As a declaration of a [define-library](define-library.md) its body is more declarations. A requirement is:

- a feature identifier, one of those [features](features.md) returns;
- `(library name)`, which holds when `(import name)` would find the library: a standard library this front end has, a `define-library` before it, or a library file;
- `(and requirement...)`, `(or requirement...)`, `(not requirement)`.

The clause is chosen when the program is read, so a compiled program carries only the body it took. A `cond-expand` no clause of which holds, with no `else`, is an error; R7RS leaves it unspecified.

```scheme
(cond-expand (ratios (/ 1 3)) (else 0.33)) ; => 1/3
(cond-expand ((and r7rs (not gauche)) 'here) (else 'there)) ; => here
(cond-expand ((library (scheme char)) 'has-char) (else 'no-char)) ; => has-char
```

```scheme
(import (scheme base))
(cond-expand
  ((library (scheme write)) (import (scheme write)))
  (else))
(define (third x)
  (cond-expand
    (exact-closed (define result (/ x 3)))
    (else (define result (* x 1/3))))
  result)
(write (third 2))
(newline)
```

```
2/3
```
