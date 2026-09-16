# array-in-bounds-p

`(array-in-bounds-p array &rest subscripts)`

Returns true when every subscript is a valid index into `array`, `nil` otherwise -- without signaling. A non-array, a subscript count that does not match the rank, and any negative or out-of-range subscript all answer `nil`. Strings count (they are rank-1 character arrays); the dimension, not the fill pointer, is what a subscript is checked against.

```lisp
(array-in-bounds-p (make-array '(2 3)) 1 2) ; => T
```

```lisp
(array-in-bounds-p (make-array '(2 3)) 2 0) ; => NIL
```

```lisp
(array-in-bounds-p "abc" 3) ; => NIL
```
