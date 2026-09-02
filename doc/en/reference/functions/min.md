# min

`(min &rest numbers)`

Returns the smallest of its arguments. It is variadic and requires at least one argument. Comparison is by numeric value, mixing integers, ratios and floats freely.

It folds left as `(a <= b) ? a : b`, using the IEEE comparison. Two consequences are worth knowing when floats are involved: arguments that compare equal leave the **leftmost** one standing, so `(min -0.0 0.0)` is `-0.0` while `(min 0.0 -0.0)` is `0.0`; and a `NaN` is unordered, so the comparison fails and the **right** operand wins -- `(min nan 1.0)` is `1.0`, `(min 1.0 nan)` is `NaN`. Every backend answers the same way.

```lisp
(min 5 2 8 1) ; => 1
```
