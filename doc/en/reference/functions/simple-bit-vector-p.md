# simple-bit-vector-p

`(simple-bit-vector-p object)`

Returns true when `object` is a SIMPLE bit vector: a [`bit-vector-p`](bit-vector-p.md) with no fill pointer, not adjustable and not displaced. It answers exactly what `(typep object 'simple-bit-vector)` does.

```lisp
(simple-bit-vector-p #*0110) ; => T
```

```lisp
(simple-bit-vector-p (make-array 4 :element-type 'bit :fill-pointer 0)) ; => NIL
```

```lisp
(simple-bit-vector-p 0) ; => NIL
```
