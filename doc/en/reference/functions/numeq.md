# =

`(= &rest numbers)`

Returns `t` if all of its arguments are numerically equal, else `nil`. It is variadic and compares by numeric value across types, so an integer and an equal float or ratio compare equal (unlike `eql`). With a single argument it returns `t`.

```lisp
(= 3 3 3) ; => T
```
