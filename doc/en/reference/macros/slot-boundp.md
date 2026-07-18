# slot-boundp

`(slot-boundp instance 'slot-name)`

Whether the instance's class has the named slot. Lite: slots are always initialized (nil default), so there is no distinct unbound state — the test answers `t` for every slot the class defines ([`slot-makunbound`](slot-makunbound.md) stores nil rather than unbinding).

Supported on the **interpreter only** for now; the JVM and WASM compilers do not support it yet.

```lisp
(defclass point () ((x :initarg :x)))
(slot-boundp (make-instance 'point :x 1) 'x) ; => t
```
