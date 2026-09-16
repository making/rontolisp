# simple-bit-vector-p

`(simple-bit-vector-p object)`

Returns true when `object` is a SIMPLE bit vector. Like [`bit-vector-p`](bit-vector-p.md), it answers `nil` for every value today: no bit-vector value exists yet, and simplicity is decided together with the representation. It answers exactly what `(typep object 'simple-bit-vector)` does.

```lisp
(simple-bit-vector-p (vector 0 1)) ; => NIL
```

```lisp
(simple-bit-vector-p 0) ; => NIL
```
