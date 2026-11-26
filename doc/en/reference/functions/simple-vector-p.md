# simple-vector-p

`(simple-vector-p object)`

Returns true when `object` is a SIMPLE vector: a rank-1 array with element type `t`, no fill pointer, not `:adjustable`, and not displaced. A [`vector`](vector.md) literal and a plain [`make-array`](make-array.md) result are simple; a string (element type `character`), a packed vector, a fill-pointered or adjustable array, and a displaced view are not. It answers exactly what `(typep object 'simple-vector)` does.

```lisp
(simple-vector-p (vector 1 2)) ; => T
```

```lisp
(simple-vector-p "abc") ; => NIL
```

```lisp
(simple-vector-p (make-array 4 :fill-pointer 0)) ; => NIL
```
