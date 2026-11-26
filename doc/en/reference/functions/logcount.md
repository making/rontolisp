# logcount

`(logcount integer)`

Returns the number of 1 bits of a non-negative integer, and the number of 0 bits of a negative one (the two's-complement population count).

```lisp
(logcount 7) ; => 3
```

```lisp
(logcount -1) ; => 0
```
