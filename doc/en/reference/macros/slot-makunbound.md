# slot-makunbound

`(slot-makunbound instance 'slot-name)`

Makes the named slot unbound and returns the instance: [`slot-boundp`](slot-boundp.md) then answers `nil` and a read through [`slot-value`](slot-value.md) or an accessor signals `unbound-slot`. Storing into the slot binds it again.

On the JVM and WASM compilers the slot name must be a literal quoted symbol, like [`slot-value`](slot-value.md); a runtime-computed slot name works on the interpreter only.

```lisp
(defclass sm-point () ((x :initarg :x)))
(let ((p (make-instance 'sm-point :x 1)))
  (slot-makunbound p 'x)
  (handler-case (slot-value p 'x)
    (unbound-slot (e) (cell-error-name e)))) ; => X
```
