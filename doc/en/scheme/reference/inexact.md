# inexact

`(inexact z)`

Returns the flonum closest to `z`. The inexact of a complex is the complex of the inexact parts.

```scheme
(inexact 1/3) ; => 0.3333333333333333
(inexact 7) ; => 7.0
```
