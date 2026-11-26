# memv

`(memv obj list)`

Like `memq`, but compares with `eqv?`, so numbers and characters are found by value.

```scheme
(memv 101 '(100 101 102)) ; => (101 102)
(memv 1.0 '(1 1.0)) ; => (1.0)
```
