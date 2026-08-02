# slot-value

`(slot-value object 'slot-name)`

Reads a slot of a [`defclass`](../special-forms/defclass.md) instance, and is also a `setf`-able place. A **literal quoted symbol** slot name resolves to the slot's fixed position at compile/expansion time. On the interpreter a computed slot name (a variable or expression) is also accepted and resolves the slot at runtime by name; on the compiled backends it is an error. With a literal name, a slot name used at *different* positions by two unrelated classes is rejected as ambiguous (within one inheritance chain positions always agree — prefer `:accessor`/`:reader` functions, which are per-class). Reading does not type-check the object, like `defstruct` accessors.

A slot name no class in the program declares is a **run-time** error on every backend (`The slot X is missing`), not a compile-time one -- the same as reading it on the interpreter, and catchable with [`handler-case`](handler-case.md).

```lisp
(defclass user () ((name :initarg :name)))
(setq u (make-instance 'user :name "Alice"))
(setf (slot-value u 'name) (concatenate 'string (slot-value u 'name) "!"))
(slot-value u 'name) ; => "Alice!"
```
