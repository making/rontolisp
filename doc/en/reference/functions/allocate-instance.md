# allocate-instance

`(allocate-instance class &rest initargs)`

Returns a fresh instance of `class` -- a class metaobject (the [`find-class`](find-class.md) / [`class-of`](class-of.md) answer) or a class name symbol -- with EVERY slot unbound: no `:initform` runs and `initialize-instance` is not called. This is the low-level allocation step object mappers build on (allocate, then fill each slot with `(setf (slot-value ...))`); reading a slot before it is written signals `unbound-slot`. The `initargs` are accepted and ignored, as in Common Lisp; only methods on `allocate-instance` see them. A method keeps the system's for every other class, which `call-next-method` reaches, and [`make-instance`](../macros/make-instance.md) of a class defined with a `:metaclass` calls `allocate-instance` with the class metaobject and its initargs, then fills the slots of what it answers (a method on `standard-class` itself, or on a class's `eql`, is not reached from `make-instance` of a class without a `:metaclass`). Only `defclass` / `define-condition` classes can be allocated, plus a class metaobject made at run time by `make-instance` of a metaclass (see [`defclass`](../special-forms/defclass.md)), whose instance has no slots; a built-in class or a `defstruct` class signals an error.

```lisp
(defclass point () ((x :initarg :x :initform 0) (y :initarg :y)))
(let ((p (allocate-instance (find-class 'point))))
  (setf (slot-value p 'x) 10)
  (list (slot-boundp p 'y) (slot-value p 'x))) ; => (NIL 10)
```
