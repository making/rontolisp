# <=

`(<= &rest numbers)`

Returns `t` if its arguments are in non-decreasing order (each less than or equal to the next), else `nil`. It is variadic and compares by numeric value across integer, ratio and float types. With a single argument it returns `t`.

```lisp
(<= 1 1 2) ; => T
```
