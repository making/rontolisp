# fill-pointer

`(fill-pointer vector)`

Returns the fill pointer of a vector created with [`make-array`](make-array.md) `:fill-pointer`. The fill pointer is the vector's effective length: `length` and printing stop at it, while [`aref`](aref.md) can still reach the full backing storage. It is a `setf` place, so `(setf (fill-pointer v) n)` moves it to any position between 0 and the vector's total size. An array without a fill pointer -- a string literal, a simple or packed vector, a rank-2 array -- signals a `type-error` whose expected type is `(and vector (satisfies array-has-fill-pointer-p))`: `FILL-POINTER: The value "abc" is not of type (AND VECTOR (SATISFIES ARRAY-HAS-FILL-POINTER-P))` (test with [`array-has-fill-pointer-p`](array-has-fill-pointer-p.md) first). A `vector` that is no array at all signals a `type-error` whose expected type is `array`: `FILL-POINTER: The value 5 is not of type ARRAY`. On the wasm-GC backends both need a program with a catching form; without one they trap. `vector-push`, `vector-push-extend` and `vector-pop` report both the same way under their own names, a store as `(SETF FILL-POINTER)`, and `adjust-array` and the other accessors of an array's shape report the second. A stored value that is no integer between 0 and the vector's total size signals a `type-error` whose expected type is `(integer 0 size)`: `(SETF FILL-POINTER): The value 9 is not of type (INTEGER 0 5)`.

```lisp
(defparameter *v* (make-array 5 :fill-pointer 2 :initial-element 0))
(fill-pointer *v*) ; => 2
(setf (fill-pointer *v*) 4) ; => 4
(length *v*) ; => 4
```
