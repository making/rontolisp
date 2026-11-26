# else

`(cond ... (else expression...))` `(case key ... (else expression...))`

Auxiliary syntax: the last clause of a `cond` or `case`, taken when no clause before it was. In `case`, `(else => receiver)` calls `receiver` with the key. It has no meaning on its own.

```scheme
(cond ((> 1 2) 'a) (else 'b)) ; => b
(case 9 ((1 2) 'low) (else 'high)) ; => high
```
