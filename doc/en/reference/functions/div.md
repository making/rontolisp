# /

`(/ number &rest numbers)`

Divides the first argument by the rest, left to right; with one argument returns its reciprocal. Integer division is exact: when the result is not a whole number it is returned as a reduced ratio rather than truncated, and an evenly dividing pair yields an integer. If any argument is a float the result is a float. Dividing by zero signals an error.

```lisp
(/ 1 2) ; => 1/2
```

```lisp
(/ 10 2) ; => 5
```
