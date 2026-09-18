# log

`(log z)` `(log z base)`

Returns the natural logarithm of `z`, or with two arguments the logarithm of `z` to `base`. `(log 1)` is the exact `0`, and `(log 0)` is `-inf.0`. There are no complex numbers: the logarithm of a negative number is refused with an error naming `log`.

```scheme
(log 1) ; => 0
(log 100 10) ; => 2.0
(log 0) ; => -inf.0
```
