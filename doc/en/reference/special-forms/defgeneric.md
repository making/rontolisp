# defgeneric

`(defgeneric name (param...) option...)`

Defines a generic function and returns the name symbol. Methods are added with [`defmethod`](defmethod.md) — specializers may appear on **any** required parameter, and a call runs the most specific matching method (parameters ranked leftmost-first); calling the generic with no matching method signals an error. A `defgeneric` is optional — the first `defmethod` implicitly creates the generic — but declares the lambda list every method must match. The generic function is an ordinary function, so `#'name` and `funcall` work. `name` may also be a setf function name `(setf reader)` (see [defmethod](defmethod.md)).

The lambda list may continue past the required parameters with `&optional`/`&rest` (the dispatcher forwards the tail to the selected method), and inline `(:method [qualifier] (param...) body...)` clauses define methods in the `defgeneric` itself. `(:documentation "...")` is recorded and ignored.

`(:method-combination NAME [:most-specific-first | :most-specific-last])` selects one of the CLHS **short-form** combinations — `progn`, `and`, `or`, `+`, `list`, `nconc`, `append`, `max`, `min`. The effective method is then that operator applied to EVERY applicable method whose qualifier is the combination name, most specific first (`:most-specific-last` reverses the order); `:around` methods wrap the combined form as usual, while `:before`/`:after` are rejected, as CLHS requires. A primary method must carry the combination name as its qualifier: `(defmethod encode-slots progn ((o point)) ...)`.

```lisp
(defclass shape () ())
(defclass square (shape) ())
(defgeneric describe-parts (x) (:method-combination list))
(defmethod describe-parts list ((x shape)) 'shape)
(defmethod describe-parts list ((x square)) 'square)
(describe-parts (make-instance 'square)) ; => (SQUARE SHAPE)
```

Lite subset: `&key` in the generic's lambda list, `define-method-combination` (the long form) and the remaining options are errors.

```lisp
(defgeneric area (shape)
  (:documentation "The area of a shape."))
(defmethod area (shape) 0)
(defmethod area ((shape (eql :unit-square))) 1)
(list (area :unit-square) (area :dot) (funcall #'area :unit-square)) ; => (1 0 1)
```

Calling a generic that has no applicable method signals an error (`No applicable method: G on INTEGER`), so it is shown here statically rather than as a runnable example:

```console
(defgeneric g (x))
(g 1)
```
