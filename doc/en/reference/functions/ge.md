# >=

`(>= &rest numbers)`

Returns `t` if its arguments are in non-increasing order (each greater than or equal to the next), else `nil`. It is variadic and compares by numeric value across integer, ratio and float types. With a single argument it returns `t`.

```lisp
(>= 2 1 1) ; => t
```
