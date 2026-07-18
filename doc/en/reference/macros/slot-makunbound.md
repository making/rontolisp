# slot-makunbound

`(slot-makunbound instance 'slot-name)`

Lite: stores nil into the slot (rontolisp has no distinct unbound slot state) and returns the instance.

On the JVM and WASM compilers the slot name must be a literal quoted symbol, like [`slot-value`](slot-value.md); a runtime-computed slot name works on the interpreter only.

```lisp
(defclass point () ((x :initarg :x)))
(let ((p (make-instance 'point :x 1)))
  (slot-makunbound p 'x)
  (slot-value p 'x)) ; => nil
```
