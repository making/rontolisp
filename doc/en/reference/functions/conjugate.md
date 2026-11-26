# conjugate

`(conjugate number)`

Returns the complex conjugate of `number`: the imaginary part negated for a complex, the number itself for a real. Works on all four backends.

```lisp
(conjugate #c(1 2)) ; => #C(1 -2)
```

```lisp
(conjugate 5) ; => 5
```
