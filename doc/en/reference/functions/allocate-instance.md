# allocate-instance

`(allocate-instance class &rest initargs)`

Returns a fresh instance of `class` -- a class metaobject (the [`find-class`](find-class.md) / [`class-of`](class-of.md) answer) or a class name symbol -- with EVERY slot unbound: no `:initform` runs and `initialize-instance` is not called. This is the low-level allocation step object mappers build on (allocate, then fill each slot with `(setf (slot-value ...))`); reading a slot before it is written signals `unbound-slot`. The `initargs` are accepted and ignored, as in Common Lisp (they are only seen by methods on `allocate-instance`, which the static subset does not support). Only `defclass` / `define-condition` classes can be allocated; a built-in class or a `defstruct` class signals an error.

```lisp
(defclass point () ((x :initarg :x :initform 0) (y :initarg :y)))
(let ((p (allocate-instance (find-class 'point))))
  (setf (slot-value p 'x) 10)
  (list (slot-boundp p 'y) (slot-value p 'x))) ; => (NIL 10)
```
