# typep

`(typep object 'type-specifier)`

Tests whether `object` is of the given type. Lite: the type specifier is normally a literal (quoted) type — the same set [`typecase`](typecase.md) supports (atomic names, registered classes, zero-parameter user [`deftype`](deftype.md) names, and the compound specifiers `(or ...)`/`(and ...)`/`(not ...)`/`(member ...)`/`(eql ...)`/`(satisfies ...)`/ranged numerics/`(unsigned-byte n)`/`(signed-byte n)`/the array family); an unknown specifier matches nothing.

The array family is `(array ELEMENT-TYPE DIMENSIONS)`, `(simple-array ELEMENT-TYPE DIMENSIONS)`, `(vector ELEMENT-TYPE SIZE)` and `(simple-vector SIZE)` — the specifiers [`type-of`](../functions/type-of.md) builds. Both halves are checked: the element type against the array's upgraded [`array-element-type`](../functions/array-element-type.md), the dimensions against its own. `DIMENSIONS` may be a list (`*` in any position means "any size"), a bare rank, `nil` for a rank-0 array, or `*`; both `vector` spellings pin the rank to 1.

A specifier computed at run time is supported when it is an ATOMIC type name (a registered class / struct / condition, or a built-in name) or a class metaobject — what [`find-class`](../functions/find-class.md) and [`class-of`](../functions/class-of.md) answer designates its own class. The compound specifiers above still require a literal. `class` is the class every class metaobject belongs to, so `(typep x 'class)` is the "is this a class?" test.

```lisp
(typep 5 '(unsigned-byte 8)) ; => T
```

```lisp
(typep 500 '(unsigned-byte 8)) ; => NIL
```

```lisp
(defclass animal () ())
(defclass dog (animal) ())
(list (typep (make-instance 'dog) (find-class 'animal))
      (typep (find-class 'dog) 'class)
      (typep 42 'class)) ; => (T T NIL)
```
