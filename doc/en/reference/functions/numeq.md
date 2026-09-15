# =

`(= &rest numbers)`

Returns `t` if all of its arguments are numerically equal, else `nil`. It is variadic and compares by numeric value across types, so an integer and an equal float or ratio compare equal (unlike `eql`). A float beside an exact number compares exact values -- the float counts at its precise binary value, so a ratio that merely rounds to the float is not equal. With a single argument it returns `t`.

```lisp
(= 3 3 3) ; => T
(= 0.5 1/2) ; => T
(= 1.0 (+ 1 (/ 1 (ash 1 60)))) ; => NIL
```
