# type-of

`(type-of object)`

The type name of a value as a symbol: a `defstruct`/CLOS instance answers its structure/class NAME, any other value answers a built-in type-name symbol (`integer`, `string`, `cons`, ...), falling back to `t`. It is the name-only view of what [`class-of`](class-of.md) answers as a class metaobject: `(type-of x)` and `(class-name (class-of x))` agree. A class defined in another package answers its package-qualified name — one colon when the package exports it, two when it does not — whatever package the caller is in.

```lisp
(type-of 42) ; => INTEGER
```

An ARRAY answers a COMPOUND specifier instead, so the rank and the element type are readable. A NON-simple array — one with a fill pointer, `:adjustable t` or a displacement — is `(vector ELEMENT-TYPE SIZE)` at rank 1 and `(array ELEMENT-TYPE DIMENSIONS)` above it. A simple one is `(simple-vector SIZE)` when it is a rank-1 array of `t`, and `(simple-array ELEMENT-TYPE DIMENSIONS)` otherwise — the rank-0 array included, whose dimension list is `nil`. The element type is [`array-element-type`](array-element-type.md)'s upgraded answer, so an array asked for `:element-type 'fixnum` reads back as `t`. A string answers the atomic `string`.

```lisp
(list (type-of (make-array 4))
      (type-of (make-array nil))
      (type-of (make-array '(2 2) :element-type 'double-float))
      (type-of (make-array 4 :element-type '(unsigned-byte 8)))
      (type-of (make-array 4 :fill-pointer 0))
      (type-of (make-array 2 :displaced-to (make-array 4))))
; => ((SIMPLE-VECTOR 4) (SIMPLE-ARRAY T NIL) (SIMPLE-ARRAY DOUBLE-FLOAT (2 2)) (SIMPLE-ARRAY (UNSIGNED-BYTE 8) (4)) (VECTOR T 4) (VECTOR T 2))
```

```lisp
(defpackage :gfx (:use :cl) (:export :sprite))
(in-package :gfx)
(defclass sprite () ())
(defclass hidden () ())
(defpackage :game (:use :cl))
(in-package :game)
(list (type-of (make-instance 'gfx:sprite))
      (type-of (make-instance 'gfx::hidden))) ; => (GFX:SPRITE GFX::HIDDEN)
```
