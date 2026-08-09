# defstruct

`(defstruct name slot...)`

Defines a structure type named `name` and returns the name symbol. Each `slot` is either a symbol or `(slot-name default)`, where `default` is evaluated at construction time when the slot is not supplied (and may refer to variables in scope). The form generates ordinary functions:

- `make-name (&key slot...)` — the constructor; slots are supplied as keyword arguments, an unknown keyword is an error
- `name-p (object)` — the type predicate, `t` for instances of this structure only
- `copy-name (object)` — a shallow copier
- `name-slot (object)` — one accessor per slot; accessors are also `setf`-able places, so `setf`/`incf`/`push` on `(name-slot obj)` work

Because the generated names are plain functions they are first-class (`#'point-x`, `mapcar`, `funcall`). On the compilation path `defstruct` is only supported as a top-level form; the interpreter also accepts it in the REPL and via `load`. Under a [user-defined package](../packages.md#user-defined-packages-defpackage) the generated names are interned as internal symbols of that package (`geo::make-pt`); listing them in a `defpackage` `:export` clause is not supported.

An instance is a first-class structure object, not a list: `print` shows it in the standard `#S(NAME :SLOT value ...)` syntax, `consp`/`listp` are `nil` on instances, and `equal` compares instances slot-wise (Common Lisp compares distinct structures as unequal). The options syntax `(defstruct (name option...) slot...)` supports `(:constructor name)`, `(:conc-name prefix)`, `(:predicate name)`, `(:copier name)`, `(:include parent (slot new-default) ...)`, `(:type (vector ...))`, `(:print-object fn)` and `(:print-function fn)` on every backend, and a documentation string before the slots is accepted and dropped. A BOA constructor -- `(:constructor name (lambda-list))` -- is supported in a lite form: a slot named by the lambda list reads that parameter, and every other slot evaluates its initform in the constructor body. Slot options `:type` and `:read-only` are parsed and ignored. The struct name is usable as a [`defmethod`](defmethod.md) parameter specializer, and the runtime `eval` of a compiled program knows neither `defstruct` nor accessor `setf` places (calling the generated functions from `eval` works).

`(:include parent)` is single struct inheritance: the parent's slots come first (so its accessors, its predicate and `(typep x 'parent)` all work on a child instance), and the child adds its own after them. A trailing slot-override -- `(:include parent (slot new-default) ...)` -- re-defaults one inherited slot in THIS child's layout (the parent's own default is untouched); the slot keeps its inherited index, so the parent's accessors still read it. Overriding a slot the parent does not define is an error. `(:type (vector ...))` makes the "instance" a plain vector instead of a structure object: the element type is dropped (rontolisp vectors are generic), accessors are `aref` reads and `setf`-able places, the copier is `copy-seq`, and since there is no structure tag such a type has no predicate, no `#S(...)` syntax and cannot be a `defmethod` specializer (Common Lisp agrees -- a typed struct is not a `structure-object`). `:include` on a `:type` struct is an error.

`(:print-object fn)` and `(:print-function fn)` give the structure its own printer instead of the `#S(...)` syntax. `fn` is a function designator -- a symbol or a `lambda` expression; `:print-object` calls it with `(object stream)` and the older `:print-function` with `(object stream depth)`, where `depth` is always `0` (no print level is tracked). Either one is exactly a [`print-object`](defmethod.md) method on the structure type, so every printing operator honors it -- `print`, `princ`, `prin1`, `format`'s `~A`/`~S` -- and a later `defmethod print-object` on the same type replaces it. [`print-unreadable-object`](../macros/print-unreadable-object.md) is the usual way to write the body. Giving both options is an error, and so is combining either with `:type` (a typed structure is a plain vector, with no type to dispatch on).

```lisp
(defstruct (celsius (:print-object (lambda (obj stream)
                                     (format stream "~D deg" (celsius-c obj)))))
  (c 0))
(list (princ-to-string (make-celsius :c 21)) (format nil "~A" (make-celsius)))
; => ("21 deg" "0 deg")
```

```lisp
(defstruct shape (kind :none))
(defstruct (circle (:include shape)) (r 1))
(setq c (make-circle :kind :round :r 2))
(list (shape-kind c) (circle-r c) (shape-p c) (circle-p (make-shape))) ; => (:ROUND 2 T NIL)
```

```lisp
(defstruct point x (y 10))
(setq p (make-point :x 1))
(list (point-x p) (point-y p) (point-p p) (point-p '(1 2))) ; => (1 10 T NIL)
```

The same `#S(NAME :SLOT value ...)` syntax is also read back: a `#S(...)` literal in source is a self-evaluating instance, so it works quoted, inside a backquote template and inside a `#(...)` vector literal. The `defstruct` must appear in an EARLIER top-level form (the literal is built as the source is processed, just as it is in Common Lisp). Slot values are read as data and are never evaluated, so `#S(BOX :V (+ 1 2))` stores the list `(+ 1 2)`; a slot named twice keeps its leftmost value; an omitted slot takes its `default`, which here must be a constant rather than an expression to evaluate. A type name that is not a structure, a slot the type does not have, and an odd number of slot items are errors. The runtime [`read`](../functions/read.md) / `read-from-string` builds the instance too, on every backend, so `(read-from-string (prin1-to-string p))` round-trips everywhere. One compiled-reader nuance: an omitted slot whose `default` is `nil` or a simple constant is honored at run time, while a `default` outside the re-readable constant set signals instead of substituting a wrong value (see [Compiled read/load Limitations](../../guides/read-load-limitations.md)).

```lisp
(defstruct point x (y 10))
(list #S(POINT :X 1 :Y 2) #S(POINT :X 7) (equal #S(POINT :X 1 :Y 2) (make-point :x 1 :y 2)))
; => (#S(POINT :X 1 :Y 2) #S(POINT :X 7 :Y 10) T)
```

```lisp
(defstruct book title (sold 0))
(setq b (make-book :title "RontoLisp"))
(incf (book-sold b))
(setf (book-title b) "RontoLisp 2e")
(setq c (copy-book b))
(incf (book-sold c))
(list (book-title b) (book-sold b) (book-sold c)) ; => ("RontoLisp 2e" 1 2)
```
