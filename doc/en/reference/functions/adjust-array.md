# adjust-array

`(adjust-array array new-dimensions &key initial-element fill-pointer)`

Resizes `array` to `new-dimensions` (an integer for a vector, or a list of the same rank as `array`), preserving the elements at the subscripts valid in both shapes -- resizing a matrix keeps `(i, j)` at `(i, j)`, not at the same flat position. New cells are set to `:initial-element`, defaulting to the array's element type's own zero -- the same fill [`make-array`](make-array.md) gives an unsupplied element: `#\Space` for a character array, `0` or `0.0` for a declared integer width or float type, `nil` for element type `t`. An array created [`:adjustable`](make-array.md) is adjusted in place and returned itself (`eq` to the argument), so every reference sees the new shape; otherwise a fresh array is returned and the original is left untouched -- use the return value either way. Without an explicit `:fill-pointer` the array's own [fill pointer](fill-pointer.md) carries over (an error if it no longer fits); `t` sets it to the new size, an integer to that position. Adjusting a displaced view or passing `:displaced-to` is not supported and signals an error.

```lisp
(defparameter *v* (make-array 3 :adjustable t :initial-element 1))
(eq (adjust-array *v* 5 :initial-element 9) *v*) ; => T
*v* ; => #(1 1 1 9 9)
(adjust-array (make-array '(2 2) :initial-element 5) '(2 3) :initial-element 0) ; => #2A((5 5 0) (5 5 0))
```
