# bit-vector-p

`(bit-vector-p object)`

Returns true when `object` is a bit vector: a rank-1 array stamped with the remembered element type `bit` -- a `#*` literal or a [`make-array`](make-array.md) result with `:element-type 'bit`. It answers exactly what `(typep object 'bit-vector)` does. A bit vector prints as the general vector holding 0/1 (there is no packed bit storage on any backend).

```lisp
(bit-vector-p #*0110) ; => T
```

```lisp
(bit-vector-p (make-array 4 :element-type 'bit)) ; => T
```

```lisp
(bit-vector-p (vector 0 1)) ; => NIL
```
