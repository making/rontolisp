# slot-value

`(slot-value object 'slot-name)`

Reads a slot of a [`defclass`](../special-forms/defclass.md) instance, and is also a `setf`-able place. The slot name must be a **literal quoted symbol**; it resolves to the slot's fixed position at compile/expansion time, so a computed slot name is an error, and a slot name used at *different* positions by two unrelated classes is rejected as ambiguous (within one inheritance chain positions always agree — prefer `:accessor`/`:reader` functions, which are per-class). Reading does not type-check the object, like `defstruct` accessors.

```lisp
(defclass user () ((name :initarg :name)))
(setq u (make-instance 'user :name "Alice"))
(setf (slot-value u 'name) (concatenate 'string (slot-value u 'name) "!"))
(slot-value u 'name) ; => "Alice!"
```
