# dec'

`(dec' x)`

One less, with overflow checking. Integers are bignums here, so this is `dec`:
it never overflows. Anything but a number is refused with the oracle's class
(`NullPointerException` for `nil`, `ClassCastException` otherwise). As a value
one argument.

```clojure
(println (dec' 5)) ; 4
(println (map dec' '(1 2))) ; (0 1)
```
