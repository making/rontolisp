# aref

`(aref array &rest subscripts)`

Returns the element of `array` at the given 0-based subscripts, one per dimension (none for a rank-0 array, one for a rank-1 vector, two for a rank-2 array, and so on). A string is a rank-1 character array, so `(aref s i)` reads like [`char`](char.md) (writing a string element goes through the `schar`/`char` setf place instead). Flat rank-independent access is available via [`row-major-aref`](row-major-aref.md). To modify an element, use `aref` as a `setf` place: `(setf (aref array i j) value)`, which also works with `incf`/`decf`/`push`. `#'aref` is a first-class function value, so it can be passed to `mapcar`/`funcall` like any other function.

Each subscript must lie within its own dimension: one that does not signals a `type-error` whose datum is the subscript and whose expected type is `(integer 0 (d))`, `d` being that dimension, reported as `AREF: The value 3 is not of type (INTEGER 0 (3))` on every backend (on the wasm-GC backends in a program with a catching form; without one it traps). A column past its dimension is out of range even when the row-major index it would fold to is not. A store reports as `(SETF AREF)`, after the stored value is evaluated and checked.

```lisp
(let ((a (make-array 3 :initial-element 0)))
  (setf (aref a 1) 9)
  (aref a 1)) ; => 9
(aref (make-array nil :initial-element 5)) ; => 5
(handler-case (aref (make-array '(2 3) :initial-element 0) 0 3)
  (type-error (e) (list (princ-to-string e) (type-error-expected-type e))))
; => ("AREF: The value 3 is not of type (INTEGER 0 (3))" (INTEGER 0 (3)))
```
