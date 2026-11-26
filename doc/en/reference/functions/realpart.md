# realpart

`(realpart number)`

Returns the real part of `number`: the real part itself for a complex, the number itself for a real. Works on all four backends.

```lisp
(realpart #c(1 2)) ; => 1
```

```lisp
(realpart 5) ; => 5
```
