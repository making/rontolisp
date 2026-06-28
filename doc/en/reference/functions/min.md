# min

`(min &rest numbers)`

Returns the smallest of its arguments. It is variadic and requires at least one argument. Comparison is by numeric value, mixing integers, ratios and floats freely.

```lisp
(min 5 2 8 1) ; => 1
```
