# defstruct

`(defstruct name slot...)`

Defines a structure type named `name` and returns the name symbol. Each `slot` is either a symbol or `(slot-name default)`, where `default` is evaluated at construction time when the slot is not supplied (and may refer to variables in scope). The form generates ordinary functions:

- `make-name (&key slot...)` — the constructor; slots are supplied as keyword arguments, an unknown keyword is an error
- `name-p (object)` — the type predicate, `t` for instances of this structure only
- `copy-name (object)` — a shallow copier
- `name-slot (object)` — one accessor per slot; accessors are also `setf`-able places, so `setf`/`incf`/`push` on `(name-slot obj)` work

Because the generated names are plain functions they are first-class (`#'point-x`, `mapcar`, `funcall`). On the compilation path `defstruct` is only supported as a top-level form; the interpreter also accepts it in the REPL and via `load`. Under a [user-defined package](../packages.md#user-defined-packages-defpackage) the generated names are interned as internal symbols of that package (`geo::make-pt`); listing them in a `defpackage` `:export` clause is not supported.

An instance is a first-class structure object, not a list: `print` shows it in the standard `#S(NAME :SLOT value ...)` syntax, `consp`/`listp` are `nil` on instances, and `equal` compares instances slot-wise (Common Lisp compares distinct structures as unequal). The options syntax `(defstruct (name option...) slot...)` supports `(:constructor name)`, `(:conc-name prefix)`, `(:predicate name)` and `(:copier name)` on every backend, and a documentation string before the slots is accepted and dropped. A BOA constructor -- `(:constructor name (lambda-list))` -- is supported in a lite form: a slot named by the lambda list reads that parameter, and every other slot evaluates its initform in the constructor body. Slot options `:type` and `:read-only` are parsed and ignored. `:include` inheritance is not supported, the struct name is usable as a [`defmethod`](defmethod.md) parameter specializer, and the runtime `eval` of a compiled program knows neither `defstruct` nor accessor `setf` places (calling the generated functions from `eval` works).

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
