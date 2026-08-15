# defmethod

`(defmethod name [qualifier] (param... ) body...)`

Adds a method to the generic function `name` (creating it when no [`defgeneric`](defgeneric.md) preceded it) and returns the name symbol. **Any** required parameter may carry a specializer, written `(var specializer)`:

- `(var (eql literal))` — matches when the first argument is the literal (a keyword, quoted symbol, number, or character)
- `(var class-name)` — matches instances of a [`defclass`](defclass.md) class and its subclasses
- `(var struct-name)` — matches instances of a [`defstruct`](defstruct.md) type (the dispatcher tests the instance tag, like the struct predicate)
- `(var type-name)` — matches a built-in type (`integer`, `float`, `number`, `string`, `symbol`, `keyword`, `character`, `cons`, `list`, `null`, `hash-table`, `function`, `pathname`, `package`, ...). A `package` parameter matches exactly what [`typep`](../macros/typep.md) calls a package, and is tried BEFORE `keyword`/`symbol`, so the designator idiom "a `package` method plus an unspecialized method that calls [`find-package`](../functions/find-package.md) and recurses" terminates
- `(var t)` or a plain `var` — the default method

A call runs the most specific matching method: parameters are ranked leftmost-first, and per parameter `eql` methods win over class methods (subclass before superclass), then built-in types (subtypes such as `integer` before their supertypes such as `number`), then the default; with no match the call signals an error. Defining the same specializer combination again replaces the previous method. The lambda list may continue past the required parameters with `&optional`/`&rest` (the dispatcher forwards the tail via `apply`). The body may start with a docstring and `(declare ...)` (both are ignored).

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

## Setf methods

`name` may also be the function name `(setf reader)`: the method becomes part of the *setf function* of `reader`, and `(setf (reader arg...) value)` dispatches through it with the new value as the FIRST parameter (CL's setf-function argument order). A setf method on the same name as a [`defclass`](defclass.md) `:accessor` merges with the accessor's writer methods instead of shadowing them, and `#'(setf reader)` is the writer as a first-class function. `(defgeneric (setf reader) ...)` works the same way, inline `(:method ...)` clauses included.

```lisp
(defclass sbox () ((v :initarg :v :reader content)))
(defmethod (setf content) (new (b sbox)) (setf (slot-value b 'v) new))
(let ((b (make-instance 'sbox :v 1)))
  (setf (content b) 42)
  (content b)) ; => 42
```

## Method qualifiers and `call-next-method`

An optional `:before`, `:after`, or `:around` **qualifier** before the lambda list adds an auxiliary method (standard method combination). For one call:

- every applicable `:around` method runs, most specific first, each wrapping the rest;
- then every `:before` method runs for effect, most specific first;
- then the most specific applicable primary (unqualified) method runs — its value is the result;
- then every `:after` method runs for effect, **least** specific first.

Under a short-form [`:method-combination`](defgeneric.md) the qualifier set is different: a primary method carries the COMBINATION NAME instead (`(defmethod total + ((x account)) ...)`), `:around` still wraps, and `:before`/`:after` are rejected.

Inside a primary or `:around` method, `(call-next-method)` invokes the next less specific method (passing the current arguments, or new ones if given as `(call-next-method arg...)`), and `(next-method-p)` returns whether such a method exists. Calling `call-next-method` with no next method signals an error.

```lisp
(defclass point () ((x :initarg :x :accessor px)))
(defclass point3d (point) ((z :initarg :z :accessor pz)))
(defgeneric describe-point (p))
(defmethod describe-point ((p point)) (list :x (px p)))
(defmethod describe-point ((p point3d)) (append (call-next-method) (list :z (pz p))))
(defmethod describe-point :around ((p point)) (list :point (call-next-method)))
(describe-point (make-instance 'point3d :x 1 :z 3)) ; => (:POINT (:X 1 :Z 3))
```

Lite subset: `&key` is an error, and standard method combination is supported for class and default methods (an `:around`/`:before`/`:after` with an `eql` or built-in-type specializer combines only with primaries of the same specializer plus the default method). On the compilation path `defmethod` is only supported as a top-level form; the dispatched method set of a compiled program is fixed at compile time.

## A method on a built-in name

Defining a method on the name of a built-in function (`close`, `open-stream-p`, `stream-element-type`, ...) makes the **built-in the generic function's default method**: instances of the specialized class run the method, and every other argument keeps the built-in behavior — including through a `(call-next-method)` out of the least specific primary method. A method on `close` for your own stream class therefore leaves `(close stream)` on a real file stream working. A user default (unspecialized) method still replaces the built-in outright.

```lisp
(defclass counter () ((n :initform 3)))
(defmethod length ((c counter)) (slot-value c 'n))
(list (length (make-instance 'counter)) (length "abcd")) ; => (3 4)
```

Lite subset: this works on every backend — the compilation paths route calls to such a name through the generated dispatcher, whose fall-through is the original built-in. Only names backed by a native built-in function participate; a name implemented as an expansion (`mapcar`, `sort`, `format`, ...) cannot take methods, and a plain `defun` on a built-in name is still ignored on the compilation paths.
