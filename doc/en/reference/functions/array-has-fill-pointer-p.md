# array-has-fill-pointer-p

`(array-has-fill-pointer-p array)`

Returns `t` when the array has a fill pointer (it was created with [`make-array`](make-array.md) `:fill-pointer`), otherwise nil. Only rank-1 arrays (vectors) can have one.

```lisp
(array-has-fill-pointer-p (make-array 3 :fill-pointer 0)) ; => t
(array-has-fill-pointer-p (make-array 3)) ; => nil
```
