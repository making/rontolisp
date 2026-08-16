# type-of

`(type-of object)`

The type name of a value as a symbol: a `defstruct`/CLOS instance answers its structure/class NAME, any other value answers a built-in type-name symbol (`integer`, `string`, `cons`, ...), falling back to `t`. It is the name-only view of what [`class-of`](class-of.md) answers as a class metaobject: `(type-of x)` and `(class-name (class-of x))` agree. A class defined in another package answers its package-qualified name — one colon when the package exports it, two when it does not — whatever package the caller is in.

```lisp
(type-of 42) ; => INTEGER
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
