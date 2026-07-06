# defmethod

`(defmethod name (param... ) body...)`

Adds a method to the generic function `name` (creating it when no [`defgeneric`](defgeneric.md) preceded it) and returns the name symbol. Only the **first** parameter may carry a specializer, written `(var specializer)`:

- `(var (eql literal))` — matches when the first argument is the literal (a keyword, quoted symbol, number, or character)
- `(var class-name)` — matches instances of a [`defclass`](defclass.md) class and its subclasses
- `(var type-name)` — matches a built-in type (`integer`, `float`, `number`, `string`, `symbol`, `keyword`, `character`, `cons`, `list`, `null`, `hash-table`, `function`, ...)
- `(var t)` or a plain `var` — the default method

A call runs the most specific matching method: `eql` methods first, then class methods (subclass before superclass), then built-in types (subtypes such as `integer` before their supertypes such as `number`), then the default method; with no match the call signals an error. Defining the same specializer again replaces the previous method. The body may start with a docstring and `(declare ...)` (both are ignored).

Lite subset: required parameters only, specializers on later parameters are errors, and method qualifiers (`:before`/`:after`/`:around`) and `call-next-method` are not supported. On the compilation path `defmethod` is only supported as a top-level form; the dispatched method set of a compiled program is fixed at compile time.

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
