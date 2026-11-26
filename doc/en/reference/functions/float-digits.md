# float-digits

`(float-digits float)`

Returns the number of radix-2 digits the representation of `float` uses, including the implicit leading bit: `53` for every float, since every float is a double. The digits a value actually holds -- fewer for a subnormal, none for a zero -- are [`float-precision`](float-precision.md)'s answer.

```lisp
(float-digits 1.5) ; => 53
```

```lisp
(float-digits 0.0) ; => 53
```
