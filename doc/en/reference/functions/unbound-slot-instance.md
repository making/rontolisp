# unbound-slot-instance

`(unbound-slot-instance condition)`

The object whose slot was unbound when an `unbound-slot` condition was signalled -- the companion of [`cell-error-name`](cell-error-name.md), which names the slot. See [`slot-boundp`](../macros/slot-boundp.md).

```lisp
(defclass usi-box () ((v)))
(handler-case (slot-value (make-instance 'usi-box) 'v)
  (unbound-slot (e) (type-of (unbound-slot-instance e)))) ; => USI-BOX
```
