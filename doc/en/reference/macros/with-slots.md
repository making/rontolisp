# with-slots

`(with-slots (slot-or-pair...) instance body...)`

Binds variables to the slot values of a CLOS-subset instance ([`defclass`](../special-forms/defclass.md) / [`define-condition`](define-condition.md)) for the body: each entry is a slot name, or a `(var slot)` pair binding `var` to slot `slot`. The instance form is evaluated once. Expands into a `let` over [`slot-value`](slot-value.md) reads.

Lite (read-only): Common Lisp's `with-slots` binds symbol macros so assignment writes back to the slot; here the bindings are plain variables, so `setq`/`setf` of one assigns the local only. Covers the dominant read-side use, e.g. condition `:report` lambdas.

```lisp
(defclass ws-point () ((x :initarg :x) (y :initarg :y)))
(with-slots (x (why y)) (make-instance 'ws-point :x 3 :y 4) (list x why)) ; => (3 4)
```
