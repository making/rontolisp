# h98. A dotted backquote tail calls the current package's `cons`

Difficulty: Low

Measured 2026-11-04.

```lisp
(defpackage :p (:use :cl) (:shadow #:cons))
(in-package :p)
(defun cons (a b) (cl:list :mine a b))
(defmacro m (x) `(a ,x . b))
(print (macroexpand-1 '(m 3)))   ; SBCL (A 3 . B)   rontolisp (:MINE A (:MINE 3 B))
```

The lowering of a dotted template names `cons` unqualified, so it resolves to a shadowing definition.
A proper-list template is unaffected, even with `list` and `append` shadowed too, so only this one
path needs `cl:cons`. Check the other names the backquote lowering spells, and `list*`.

Consumer: `concrete-syntax-tree` shadows `cons`, `first`, `rest`, `atom`, `consp`, `list` and `null`
with its own definitions (`(defgeneric cons (first rest &key source))`). Loading it stops with
`No applicable method: CONCRETE-SYNTAX-TREE:RAW on SYMBOL` at `cons-cst.lisp:18`, inside that `cons`
method called with symbols, which is what this bug would produce; confirm once it is fixed.
coalton-compiler depends on it. What coalton needs after this is unmeasured. SBCL loads coalton but
exhausts its default heap here; 16 GB was enough.

Pin in `ci-spec.yaml`.
