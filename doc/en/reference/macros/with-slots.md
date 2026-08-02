# with-slots

`(with-slots (slot-or-pair...) instance body...)`

Binds the slot names of a CLOS-subset instance ([`defclass`](../special-forms/defclass.md) / [`define-condition`](define-condition.md)) as symbol-macro-style places for the body: each entry is a slot name, or a `(var slot)` pair binding `var` to slot `slot`. The instance form is evaluated once. Reads see the slots, and [`setf`](setf.md)/`push`/`incf` of a bound name writes back to the slot (the substitution is textual over the body; an inner binding shadowing a slot variable is still substituted).

Lite: code GENERATED at run time inside the body (e.g. a `macrolet` template mentioning a slot name) resolves the name through a fallback binding holding the slot's value at entry -- reads work, but a write from such generated code updates that local copy only.

`with-slots` only BINDS -- it never reads a slot on entry -- so a body that merely assigns a slot declared without an `:initform` works, and the fallback binding above holds `nil` for such a slot. A read the body really performs still signals `unbound-slot`.

```lisp
(defclass buffered () ((buffer)))
(let ((b (make-instance 'buffered)))
  (with-slots (buffer) b (setf buffer (list 1 2)))
  (slot-value b 'buffer)) ; => (1 2)
```

```lisp
(defclass ws-point () ((x :initarg :x) (y :initarg :y)))
(with-slots (x (why y)) (make-instance 'ws-point :x 3 :y 4) (list x why)) ; => (3 4)
```

```lisp
(defclass counter () ((n :initform 0)))
(let ((c (make-instance 'counter)))
  (with-slots (n) c (incf n) (incf n))
  (slot-value c 'n)) ; => 2
```
