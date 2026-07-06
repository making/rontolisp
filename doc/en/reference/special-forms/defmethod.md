# defmethod

`(defmethod name [qualifier] (param... ) body...)`

Adds a method to the generic function `name` (creating it when no [`defgeneric`](defgeneric.md) preceded it) and returns the name symbol. Only the **first** parameter may carry a specializer, written `(var specializer)`:

- `(var (eql literal))` — matches when the first argument is the literal (a keyword, quoted symbol, number, or character)
- `(var class-name)` — matches instances of a [`defclass`](defclass.md) class and its subclasses
- `(var type-name)` — matches a built-in type (`integer`, `float`, `number`, `string`, `symbol`, `keyword`, `character`, `cons`, `list`, `null`, `hash-table`, `function`, ...)
- `(var t)` or a plain `var` — the default method

A call runs the most specific matching method: `eql` methods first, then class methods (subclass before superclass), then built-in types (subtypes such as `integer` before their supertypes such as `number`), then the default method; with no match the call signals an error. Defining the same specializer again replaces the previous method. The body may start with a docstring and `(declare ...)` (both are ignored).

```lisp
(defclass animal () ())
(defclass dog (animal) ())
(defgeneric speak (x))
(defmethod speak ((x dog)) "woof")
(defmethod speak ((x animal)) "some sound")
(defmethod speak ((x integer)) "a number")
(defmethod speak ((x (eql :cat))) "meow")
(defmethod speak (x) "?")
(list (speak (make-instance 'dog)) (speak (make-instance 'animal))
      (speak 42) (speak :cat) (speak "s")) ; => ("woof" "some sound" "a number" "meow" "?")
```

## Method qualifiers and `call-next-method`

An optional `:before`, `:after`, or `:around` **qualifier** before the lambda list adds an auxiliary method (standard method combination). For one call:

- every applicable `:around` method runs, most specific first, each wrapping the rest;
- then every `:before` method runs for effect, most specific first;
- then the most specific applicable primary (unqualified) method runs — its value is the result;
- then every `:after` method runs for effect, **least** specific first.

Inside a primary or `:around` method, `(call-next-method)` invokes the next less specific method (passing the current arguments, or new ones if given as `(call-next-method arg...)`), and `(next-method-p)` returns whether such a method exists. Calling `call-next-method` with no next method signals an error.

```lisp
(defclass point () ((x :initarg :x :accessor px)))
(defclass point3d (point) ((z :initarg :z :accessor pz)))
(defgeneric describe-point (p))
(defmethod describe-point ((p point)) (list :x (px p)))
(defmethod describe-point ((p point3d)) (append (call-next-method) (list :z (pz p))))
(defmethod describe-point :around ((p point)) (list :point (call-next-method)))
(describe-point (make-instance 'point3d :x 1 :z 3)) ; => (:point (:x 1 :z 3))
```

Lite subset: required parameters only, specializers on later parameters are errors, and standard method combination is supported for class and default methods (an `:around`/`:before`/`:after` with an `eql` or built-in-type specializer combines only with primaries of the same specializer plus the default method). On the compilation path `defmethod` is only supported as a top-level form; the dispatched method set of a compiled program is fixed at compile time.
