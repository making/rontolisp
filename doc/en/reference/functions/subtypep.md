# subtypep

`(subtypep type1 type2)`

Whether `type1` names a subtype of `type2`, answering over the built-in type lattice (e.g. `integer` ⊂ `rational` ⊂ `real` ⊂ `number`, `string` ⊂ `vector` ⊂ `array`/`sequence`) plus the class registry's ancestor sets (`defclass`/`define-condition` hierarchies). Two values, as in CL: the answer, and a `valid-p` saying whether it is a decision (see below). The float and character type names collapse to the one runtime representation, so `(subtypep 'short-float 'single-float)` is `t`; `base-string`/`simple-base-string` collapse for the same reason (one character type). The `simple-` names do NOT: a fill pointer, `:adjustable t` or a displacement makes a non-simple array or string here, so `simple-vector`/`simple-array`/`simple-string` are proper subtypes of `vector`/`array`/`string` and the reverse direction is nil. `bfloat16`, the packed-array element width ([data types](../data-types.md)), is a rontolisp extension that sits *below* `float` rather than collapsing onto it: no scalar has the type, so `(subtypep 'bfloat16 'float)` is `t` and `(subtypep 'float 'bfloat16)` is nil.

Either argument may be a class metaobject instead of a type name: what [`find-class`](find-class.md) and [`class-of`](class-of.md) answer designates its own class, so a metaobject compares exactly like the name spelling. Both arguments may also be computed at run time. On the JVM and WASM compilers a literal (quoted) pair is folded into a constant at compile time; anything else is answered at run time over the same lattice, with identical answers on all four backends.

A COMPOUND specifier works on either side, quoted or computed. `(or ...)` holds when the sub is a subtype of any branch, `(and ...)` when it is a subtype of every conjunct; as the SUB, `(or ...)` needs every branch and `(and ...)` any conjunct. Any other head as the sub reduces to that head, because a restricting specifier denotes a subset of it — so `(subtypep '(integer 0 10) 'integer)` is `t`, and so is `(subtypep (type-of a) 'vector)` on a vector. The same reduction on the SUPER would be unsound (the compound is the smaller type there), so `(subtypep 'integer '(integer 0 10))` is nil. `(not ...)`, `(member ...)`, `(eql ...)` and `(satisfies ...)` are the unknowns this lite `subtypep` answers nil for.

The SECOND value is CL's `valid-p`: whether the answer is a decision rather than an "I cannot tell". A `t` answer always is — it is only ever given on a proof. A nil answer is a decision between two plain type NAMES, which the lattice settles completely, and is `nil nil` — undecided — whenever a compound specifier is involved, because the compound rules above prove only the positive direction. So `(subtypep '(and (cons symbol *) (cons * symbol)) '(cons symbol symbol))` answers `nil nil`: the two really do denote the same type, and the pairwise rules cannot see it. Reading the second value takes a multiple-value form ([`multiple-value-bind`](../macros/multiple-value-bind.md), [`multiple-value-list`](../macros/multiple-value-list.md), [`nth-value`](../macros/nth-value.md)) — an ordinary call site sees the answer alone. The function object `#'subtypep` answers the same two values. Deviation: an unknown type NAME answers `nil t` here, where CL implementations may answer `nil nil`.

```lisp
(subtypep 'integer 'number) ; => T
```

```lisp
(multiple-value-list (subtypep 'number 'integer)) ; => (NIL T)
```

```lisp
(multiple-value-list (subtypep '(satisfies evenp) 'integer)) ; => (NIL NIL)
```

```lisp
(subtypep 'type-error 'error) ; => T
```

```lisp
(defclass animal () ())
(defclass dog (animal) ())
(list (subtypep (find-class 'dog) (find-class 'animal))
      (subtypep (find-class 'animal) (find-class 'dog))) ; => (T NIL)
```

```lisp
(list (subtypep '(integer 0 10) 'integer)
      (subtypep 'integer '(integer 0 10))) ; => (T NIL)
```

```lisp
(list (subtypep 'simple-vector 'vector)
      (subtypep 'vector 'simple-vector)) ; => (T NIL)
```

```lisp
(list (subtypep 'bfloat16 'float)
      (subtypep 'float 'bfloat16)) ; => (T NIL)
```
