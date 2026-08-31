# subtypep

`(subtypep type1 type2)`

Whether `type1` names a subtype of `type2`, answering over the built-in type lattice (e.g. `integer` ⊂ `rational` ⊂ `real` ⊂ `number`, `string` ⊂ `vector` ⊂ `array`/`sequence`) plus the class registry's ancestor sets (`defclass`/`define-condition` hierarchies). Lite: a single primary value — an unknown pair answers nil. The float and character type names collapse to the one runtime representation, so `(subtypep 'short-float 'single-float)` is `t`.

Either argument may be a class metaobject instead of a type name: what [`find-class`](find-class.md) and [`class-of`](class-of.md) answer designates its own class, so a metaobject compares exactly like the name spelling. Both arguments may also be computed at run time. On the JVM and WASM compilers a literal (quoted) pair is folded into a constant at compile time; anything else is answered at run time over the same lattice — but only for type NAMES. A COMPOUND specifier (`(integer 0 10)`, `(or a b)`) is answered on the interpreter and not by the compiled runtime dispatch, so a computed compound pair can differ between backends; [`typep`](../macros/typep.md) has no such gap.

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
