# typep

`(typep object 'type-specifier)`

Tests whether `object` is of the given type. Lite: the type specifier is normally a literal (quoted) type — the same set [`typecase`](typecase.md) supports (atomic names, registered classes, zero-parameter user [`deftype`](deftype.md) names, and the compound specifiers `(or ...)`/`(and ...)`/`(not ...)`/`(member ...)`/`(eql ...)`/`(satisfies ...)`/ranged numerics/`(unsigned-byte n)`/`(signed-byte n)`/the array family); an unknown specifier matches nothing.

The array family is `(array ELEMENT-TYPE DIMENSIONS)`, `(simple-array ELEMENT-TYPE DIMENSIONS)`, `(vector ELEMENT-TYPE SIZE)` and `(simple-vector SIZE)` — the specifiers [`type-of`](../functions/type-of.md) builds. Both halves are checked: the element type against the array's upgraded [`array-element-type`](../functions/array-element-type.md), the dimensions against its own. `DIMENSIONS` may be a list (`*` in any position means "any size"), a bare rank, `nil` for a rank-0 array, or `*`; both `vector` spellings pin the rank to 1.

The `simple-` spellings are strictly SMALLER types, not aliases: `simple-array`, `simple-vector` and `simple-string` additionally require the value to be SIMPLE — no fill pointer, not `:adjustable`, not displaced — while `array`, `vector` and `string` accept it either way. `simple-vector` is `(simple-array t (*))` exactly, so a string and a packed vector are not one.

```lisp
(let ((a (make-array 4 :fill-pointer 0)))
  (list (typep a 'vector) (typep a 'simple-vector) (typep a 'simple-array))) ; => (T NIL NIL)
```

A SIZED string specifier — `(string n)`, `(simple-string n)`, and the `(vector character n)` / `(simple-array character (n))` spellings — measures the array DIMENSION, not [`length`](../functions/length.md). A character vector's `length` is its fill pointer, so a capacity-4 vector holding one character is a `(string 4)` and not a `(string 1)`.

```lisp
(let ((s (make-array 4 :element-type 'character :fill-pointer 0)))
  (vector-push #\a s)
  (list (length s) (typep s '(string 4)) (typep s '(string 1)))) ; => (1 T NIL)
```

A specifier computed at run time is supported too, and takes the same set: an ATOMIC type name (a registered class / struct / condition, a zero-parameter [`deftype`](deftype.md) name, or a built-in name), a class metaobject — what [`find-class`](../functions/find-class.md) and [`class-of`](../functions/class-of.md) answer designates its own class — or any of the compound specifiers above, whose head and arguments are then read out of the specifier VALUE rather than folded at compile time. So `(typep a (type-of a))` answers `T` for every array shape. `class` is the class every class metaobject belongs to, so `(typep x 'class)` is the "is this a class?" test.

```lisp
(typep 5 '(unsigned-byte 8)) ; => T
```

```lisp
(typep 500 '(unsigned-byte 8)) ; => NIL
```

```lisp
(let ((a (make-array 4)))
  (list (type-of a) (typep a (type-of a)))) ; => ((SIMPLE-VECTOR 4) T)
```

A `deftype` name resolves whether it is spelled at the call site or held in a variable, inside a compound specifier as well. [`coerce`](../functions/coerce.md) with a computed result type resolves it the same way.

```lisp
(deftype octet () '(unsigned-byte 8))
(let ((ty 'octet))
  (list (typep 3 ty) (typep 300 ty) (typep 3 (list 'or ty 'null)))) ; => (T NIL T)
```

```lisp
(defclass animal () ())
(defclass dog (animal) ())
(list (typep (make-instance 'dog) (find-class 'animal))
      (typep (find-class 'dog) 'class)
      (typep 42 'class)) ; => (T T NIL)
```
