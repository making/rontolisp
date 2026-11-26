# Mixing with Common Lisp

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
