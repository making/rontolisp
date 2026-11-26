# complex

`(complex real &optional imag)`

Answers the canonical complex value for a real part and an optional imaginary part (defaulting to zero): a rational zero imaginary part demotes to the real itself, while a float zero stays complex (an integer zero beside a float part becomes `0.0`); a rational part beside a float part is coerced to a float, so exact rationals stay exact only when both parts are rational. Complexes read with `#C(real imag)` and print the same way. A non-real part signals a catchable type error. Works on all four backends.

```lisp
(complex 1 2) ; => #C(1 2)
```

```lisp
(complex 1 0) ; => 1
```

```lisp
(complex 2.0 0) ; => #C(2.0 0.0)
```
