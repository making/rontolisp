# inexact->exact

`(inexact->exact z)`

The R5RS name of `exact`: returns the exact number equal to `z`, a flonum converting to the exact value of its binary representation. It belongs to `(scheme r5rs)` and is visible only to a file with no `import`.

```scheme
(inexact->exact 0.25) ; => 1/4
(inexact->exact 2.0) ; => 2
```
