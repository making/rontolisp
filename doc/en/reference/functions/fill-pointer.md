# fill-pointer

`(fill-pointer vector)`

Returns the fill pointer of a vector created with [`make-array`](make-array.md) `:fill-pointer`. The fill pointer is the vector's effective length: `length` and printing stop at it, while [`aref`](aref.md) can still reach the full backing storage. It is a `setf` place, so `(setf (fill-pointer v) n)` moves it to any position between 0 and the vector's total size. Signals an error when the array has no fill pointer (test with [`array-has-fill-pointer-p`](array-has-fill-pointer-p.md) first). A `vector` that is no array at all signals a `type-error` whose expected type is `array`: `FILL-POINTER: The value 5 is not of type ARRAY` (on the wasm-GC backends in a program with a catching form; without one it traps). `vector-push`, `vector-pop`, `adjust-array` and the other accessors of an array's shape report the same way under their own names, a store as `(SETF FILL-POINTER)`.

```lisp
(defparameter *v* (make-array 5 :fill-pointer 2 :initial-element 0))
(fill-pointer *v*) ; => 2
(setf (fill-pointer *v*) 4) ; => 4
(length *v*) ; => 4
```
