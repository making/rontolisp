# complex

`(complex real &optional imaginary)`

Lite: there is no complex number representation, so a zero (or omitted) imaginary part yields the real part and anything else signals an error. This keeps sources whose complex branch is never taken — like parse-number's `#C(...)` parser — loadable on every backend.

```lisp
(complex 9 0) ; => 9
```

```console
CL-USER> (complex 1 2)
Error: complex numbers are not supported (imaginary part 2)
```
