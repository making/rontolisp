# slot-value

`(slot-value object 'slot-name)`

Reads a slot of a [`defclass`](../special-forms/defclass.md) instance, and is also a `setf`-able place. A **literal quoted symbol** slot name resolves to the slot's fixed position at compile/expansion time. A computed slot name (a variable or expression) is also accepted on every backend and resolves the slot at run time by name; `#'slot-value` is a function value of that shape. With a literal name, a slot name used at *different* positions by two unrelated classes is rejected as ambiguous (within one inheritance chain positions always agree — prefer `:accessor`/`:reader` functions, which are per-class). Reading does not type-check the object, like `defstruct` accessors.

A slot name no class in the program declares is a **run-time** error on every backend (`The slot X is missing`), not a compile-time one -- the same as reading it on the interpreter, and catchable with [`handler-case`](handler-case.md). A [`slot-missing`](../functions/slot-missing.md) method takes such an access over, and a [`slot-unbound`](../functions/slot-unbound.md) method the read of an unbound slot.

```lisp
(defclass user () ((name :initarg :name)))
(setq u (make-instance 'user :name "Alice"))
(setf (slot-value u 'name) (concatenate 'string (slot-value u 'name) "!"))
(slot-value u 'name) ; => "Alice!"
```
