# slot-exists-p

`(slot-exists-p instance 'slot-name)`

Whether the instance's class declares a slot of that name, regardless of boundness: an unbound slot exists ([`slot-boundp`](slot-boundp.md) is the boundness test), an undeclared one does not, and a non-instance answers `nil`. The slot name may be a runtime-computed symbol on every backend.

```lisp
(defclass se-point () ((x :initarg :x) (y :initform 0)))
(let ((p (make-instance 'se-point)))
  (list (slot-exists-p p 'x) (slot-exists-p p 'z) (slot-exists-p 42 'x))) ; => (T NIL NIL)
```
