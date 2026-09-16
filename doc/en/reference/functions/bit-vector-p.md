# bit-vector-p

`(bit-vector-p object)`

Returns true when `object` is a bit vector. No bit-vector value exists yet -- a `(make-array n :element-type 'bit)` is a plain vector holding 0/1 -- so it answers `nil` for every value today. It answers exactly what `(typep object 'bit-vector)` does, so portable code calling it loads, and keeps working when a bit-vector representation lands.

```lisp
(bit-vector-p (vector 0 1)) ; => NIL
```

```lisp
(bit-vector-p (make-array 4 :element-type 'bit)) ; => NIL
```
