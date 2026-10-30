# slot-missing

`(slot-missing class object slot-name operation &optional new-value)`

The generic function a [`slot-value`](../macros/slot-value.md) access calls when the slot name is declared by no class in the program. A read passes the operation `slot-value` and answers what the method returns; a `setf` passes `setf` and the new value, and answers the new value. The system method signals an error, so an access no method covers signals exactly as it would with no method at all.

```lisp
(defclass sm-bag () ())
(defmethod slot-missing (class (o sm-bag) name operation &optional new-value)
  (list operation name new-value))
(list (slot-value (make-instance 'sm-bag) 'color)
      (setf (slot-value (make-instance 'sm-bag) 'color) :red))
; => ((SLOT-VALUE COLOR NIL) :RED)
```

Lite: [`slot-boundp`](../macros/slot-boundp.md) of such a slot answers `nil` without calling it, and a slot some other class declares is not detected as missing.
