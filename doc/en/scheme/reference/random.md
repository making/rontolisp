# random

`(random n)`

Returns a pseudo-random number from 0 up to but not including `n`: an exact integer when `n` is an exact positive integer, an inexact number when `n` is inexact. `n` must be positive; `(random 0)` is an error. A SICP/MIT name, not R7RS: no library exports it, so it is visible only to a file with no `import` and at the REPL.

```scheme
(random 1) ; => 0
(let ((n (random 6))) (and (>= n 0) (< n 6))) ; => #t
```
