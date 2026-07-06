# make-instance

`(make-instance 'class-name :initarg value ...)`

Creates an instance of a [`defclass`](../special-forms/defclass.md) class. Slots are supplied by their `:initarg` keywords (defaulting to the slot-name keyword); an unsupplied slot takes its `:initform` (or `nil`). The class name must be a **literal quoted symbol** naming a class already defined by `defclass` — there is no runtime class table, so a computed class name is an error. Like `format`, `make-instance` is a macro with no function value, so `#'make-instance` is unsupported.

```lisp
(defclass point () ((x :initarg :x :initform 0 :reader point-x)
                    (y :initarg :y :initform 0 :reader point-y)))
(setq p (make-instance 'point :x 3))
(list (point-x p) (point-y p)) ; => (3 0)
```
