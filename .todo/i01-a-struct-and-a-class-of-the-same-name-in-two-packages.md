# i01. A defstruct and a defclass of the same name in two packages break dispatch

Difficulty: Medium

Measured 2026-11-04.

```lisp
(defpackage :a1 (:use :cl) (:export #:sa))
(in-package :a1)
(defclass sa () ())
(defpackage :a2 (:use :cl))
(in-package :a2)
(defstruct sa q)
(defgeneric g (x) (:method ((x sa)) :struct) (:method ((x a1:sa)) :class))
(print (g (make-sa)))                ; SBCL :STRUCT
                                     ; rontolisp: No applicable method: A2::G on SA
```

Two classes of one name work, and so do two structs. Only the mixed pair fails, so look where
`instance-syntax.md`'s shared value model takes a struct's tag against the class registry, probably
by name.

Consumer: cxml, the first thing it does once loaded. `sax::standard-attribute` is a defstruct and
`hax:standard-attribute` a defclass, with `sax:attribute-qname` specialized on both. Every parse and
every `with-element` stops with
`No applicable method: SAX:ATTRIBUTE-QNAME on STANDARD-ATTRIBUTE`. SBCL's output for the cxml smoke
program: the xmls parse `("a" (("x" "1")) ("b" NIL "t&u"))`, the DOM's `"a"` and `"1"`, the
sink's `<a x="1">t&lt;</a>`, a `WELL-FORMEDNESS-VIOLATION`.

Check JVM and WASM dispatch for the same pair; only the interpreter was measured.
