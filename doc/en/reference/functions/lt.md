# <

`(< &rest numbers)`

Returns `t` if its arguments are in strictly increasing order, else `nil`. It is variadic; each adjacent pair is compared by numeric value, mixing integers, ratios and floats. With a single argument it returns `t`.

```lisp
(< 1 2 3) ; => T
```
