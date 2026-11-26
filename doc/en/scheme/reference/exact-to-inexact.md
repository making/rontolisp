# exact->inexact

`(exact->inexact z)`

The R5RS name of `inexact`: returns the flonum closest to `z`. It belongs to `(scheme r5rs)`, which no `import` reaches here; it is visible only to a file with no `import`.

```scheme
(exact->inexact 1/4) ; => 0.25
(exact->inexact 3) ; => 3.0
```
