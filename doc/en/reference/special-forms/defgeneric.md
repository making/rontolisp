# defgeneric

`(defgeneric name (param...) option...)`

Defines a generic function dispatching on its first argument and returns the name symbol. Methods are added with [`defmethod`](defmethod.md); calling the generic runs the most specific method matching the first argument, and calling it with no matching method signals an error. A `defgeneric` is optional — the first `defmethod` implicitly creates the generic — but declares the lambda list every method must match. The generic function is an ordinary function, so `#'name` and `funcall` work.

Lite subset: the lambda list takes required parameters only (`&optional`/`&rest`/`&key` are errors), and the only supported option is `(:documentation "...")` (recorded and ignored) — `(:method ...)`, `:method-combination` and the rest are errors.

```lisp
(defgeneric area (shape)
  (:documentation "The area of a shape."))
(defmethod area (shape) 0)
(defmethod area ((shape (eql :unit-square))) 1)
(list (area :unit-square) (area :dot) (funcall #'area :unit-square)) ; => (1 0 1)
```

Calling a generic that has no applicable method signals an error (`No applicable method: g`), so it is shown here statically rather than as a runnable example:

```console
(defgeneric g (x))
(g 1)
```
