# log

`(log z)` `(log z base)`

Returns the natural logarithm of `z`, or with two arguments the logarithm of `z` to `base`. `(log 1)` is the exact `0`, and `(log 0)` is `-inf.0`. The logarithm of a negative number is complex: `(log -1)` is `#C(0.0 3.141592653589793)`.

```scheme
(log 1) ; => 0
(log 100 10) ; => 2.0
(log 0) ; => -inf.0
```
