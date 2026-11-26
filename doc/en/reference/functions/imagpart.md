# imagpart

`(imagpart number)`

Returns the imaginary part of `number`: the imaginary part itself for a complex, zero for a real -- an integer zero, or a float zero for a float argument. Works on all four backends.

```lisp
(imagpart #c(1 2)) ; => 2
```

```lisp
(imagpart 5.5) ; => 0.0
```
