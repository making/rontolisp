# subtypep

`(subtypep type1 type2)`

Whether `type1` names a subtype of `type2`, answering over the built-in type lattice (e.g. `integer` ⊂ `rational` ⊂ `real` ⊂ `number`, `string` ⊂ `vector` ⊂ `array`/`sequence`) plus the class registry's ancestor sets (`defclass`/`define-condition` hierarchies). Lite: a single primary value — an unknown pair answers nil. The float and character type names collapse to the one runtime representation, so `(subtypep 'short-float 'single-float)` is `t`; `base-string`/`simple-base-string` collapse for the same reason (one character type). The `simple-` names do NOT: a fill pointer, `:adjustable t` or a displacement makes a non-simple array or string here, so `simple-vector`/`simple-array`/`simple-string` are proper subtypes of `vector`/`array`/`string` and the reverse direction is nil.

Either argument may be a class metaobject instead of a type name: what [`find-class`](find-class.md) and [`class-of`](class-of.md) answer designates its own class, so a metaobject compares exactly like the name spelling. Both arguments may also be computed at run time. On the JVM and WASM compilers a literal (quoted) pair is folded into a constant at compile time; anything else is answered at run time over the same lattice, with identical answers on all four backends.

A COMPOUND specifier works on either side, quoted or computed. `(or ...)` holds when the sub is a subtype of any branch, `(and ...)` when it is a subtype of every conjunct; as the SUB, `(or ...)` needs every branch and `(and ...)` any conjunct. Any other head as the sub reduces to that head, because a restricting specifier denotes a subset of it — so `(subtypep '(integer 0 10) 'integer)` is `t`, and so is `(subtypep (type-of a) 'vector)` on a vector. The same reduction on the SUPER would be unsound (the compound is the smaller type there), so `(subtypep 'integer '(integer 0 10))` is nil. `(not ...)`, `(member ...)`, `(eql ...)` and `(satisfies ...)` are the unknowns this lite `subtypep` answers nil for.

```lisp
(subtypep 'integer 'number) ; => T
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
