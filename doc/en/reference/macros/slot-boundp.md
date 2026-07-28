# slot-boundp

`(slot-boundp instance 'slot-name)`

Whether the named slot of the instance holds a value: `nil` when the instance's class has no such slot, when the slot was written with no `:initform` and no initarg supplied it, or after [`slot-makunbound`](slot-makunbound.md); `t` otherwise. Reading an unbound slot with [`slot-value`](slot-value.md) or an accessor signals `unbound-slot`, whose [`cell-error-name`](../functions/cell-error-name.md) is the slot and whose [`unbound-slot-instance`](../functions/unbound-slot-instance.md) is the object.

On the JVM and WASM compilers the slot name must be a literal quoted symbol, like [`slot-value`](slot-value.md); a runtime-computed slot name works on the interpreter only.

```lisp
(defclass sb-point () ((x :initarg :x) (y :initform 0)))
(let ((p (make-instance 'sb-point)))
  (list (slot-boundp p 'x) (slot-boundp p 'y))) ; => (NIL T)
```
