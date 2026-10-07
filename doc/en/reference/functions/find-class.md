# find-class

`(find-class symbol &optional (errorp t) environment)`

Returns the class metaobject named by `symbol` -- a `standard-class` instance whose slots the [`class-name`](class-name.md) / `closer-mop` readers (`class-slots`, `slot-definition-name`, ...) consume. The answer is memoized, so two calls for the same class return the same (`eq`) object -- the same object [`class-of`](class-of.md) answers for an instance of that class. When no class is named `symbol`, an error is signaled unless `errorp` is `nil`, in which case `nil` is returned; `environment` is ignored. The known classes are every `defclass` / `define-condition` / `defstruct` in the program, the built-in condition hierarchy, and the built-in classes: the ones [`class-of`](class-of.md) answers (`integer`, `ratio`, `float`, `complex`, `string`, `character`, `symbol`, `keyword`, `cons`, `null`, `boolean`, `hash-table`, `function`, `vector`, `array`, the stream classes `stream`, `file-stream`, `string-stream`, `synonym-stream`, `two-way-stream`, `broadcast-stream`, `echo-stream` and `concatenated-stream`, `t`) plus the INTERIOR of the lattice, which is reachable by name only because `class-of` always has a narrower answer for it (`sequence`, `list`, `number`, `real`, `rational`, `bit-vector`, `structure-object`, `built-in-class`, `standard-object`). A built-in class metaobject carries no slots and no superclasses -- standing where a type specifier is expected it means the same set as its name, so [`typep`](../macros/typep.md) and [`subtypep`](subtypep.md) hold the lattice, not the metaobject. On the compiled backends the class set is fixed at compile time; classes built from runtime data do not exist.

```lisp
(defclass point () ((x :initarg :x)))
(list (eq (find-class 'point) (find-class 'point))
      (find-class 'no-such-class nil)) ; => (T NIL)
```

```lisp
(list (class-name (find-class 'bit-vector))
      (typep #(1 2) (find-class 'array))
      (subtypep (find-class 'vector) 'array)) ; => (BIT-VECTOR T T)
```

`(setf (find-class alias) class)` registers `class` under a second name: after it, `find-class`, `make-instance`, `typep`, `subtypep` and a `handler-case` clause all resolve the alias to the very same class (the metaobject is `eq`). Only this aliasing shape is supported -- the value must be a literal `(find-class 'target)` naming an already defined class -- and only at top level, because the compiled backends build their class table at compile time.

```lisp
(defclass shape () ((n :initarg :n :reader shape-n)))
(setf (find-class '<shape>) (find-class 'shape))
(list (eq (find-class '<shape>) (find-class 'shape))
      (shape-n (make-instance '<shape> :n 7))) ; => (T 7)
```
