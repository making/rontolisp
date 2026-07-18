# slot-makunbound

`(slot-makunbound instance 'slot-name)`

Lite: stores nil into the slot (rontolisp has no distinct unbound slot state) and returns the instance.

Supported on the **interpreter only** for now; the JVM and WASM compilers do not support it yet.

```lisp
(defclass point () ((x :initarg :x)))
(let ((p (make-instance 'point :x 1)))
  (slot-makunbound p 'x)
  (slot-value p 'x)) ; => nil
```
