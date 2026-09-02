# array-has-fill-pointer-p

`(array-has-fill-pointer-p array)`

Returns `t` when the array has a fill pointer (it was created with [`make-array`](make-array.md) `:fill-pointer`), otherwise nil. Only rank-1 arrays (vectors) can have one.

A string is a rank-1 array of characters, so it answers here too: a literal string has none, while a character vector built with `:fill-pointer` does.

```lisp
(array-has-fill-pointer-p (make-array 3 :fill-pointer 0)) ; => T
(array-has-fill-pointer-p (make-array 3)) ; => NIL
(array-has-fill-pointer-p "abc") ; => NIL
(array-has-fill-pointer-p (make-array 3 :element-type 'character :fill-pointer 0)) ; => T
```
