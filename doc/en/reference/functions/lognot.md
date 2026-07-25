# lognot

`(lognot integer)`

Returns the bitwise NOT (ones' complement) of `integer`, equivalent to `(- (+ integer 1))`. The operation is exact for arbitrarily large integers on every backend.

```lisp
(lognot 5) ; => -6
```
