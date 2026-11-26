# +'

`(+' x...)`

The sum, with overflow checking. Integers are bignums here, so this is `+`:
it never overflows, and `(+')` is `0`. Anything but a number is refused with
the oracle's class (`NullPointerException` for `nil`, `ClassCastException`
otherwise). As a value any number of arguments.

```clojure
(println (+' 1 2 3)) ; 6
(println (reduce +' [1 2 3])) ; 6
```
