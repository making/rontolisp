# slot-boundp

`(slot-boundp instance 'slot-name)`

Whether the instance's class has the named slot. Lite: slots are always initialized (nil default), so there is no distinct unbound state — the test answers `t` for every slot the class defines ([`slot-makunbound`](slot-makunbound.md) stores nil rather than unbinding).

On the JVM and WASM compilers the slot name must be a literal quoted symbol, like [`slot-value`](slot-value.md); a runtime-computed slot name works on the interpreter only.

```lisp
(defclass point () ((x :initarg :x)))
(slot-boundp (make-instance 'point :x 1) 'x) ; => T
```
