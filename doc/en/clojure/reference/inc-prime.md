# inc'

`(inc' x)`

One more, with overflow checking. Integers are bignums here, so this is `inc`:
it never overflows. Anything but a number is refused with the oracle's class
(`NullPointerException` for `nil`, `ClassCastException` otherwise). As a value
one argument.

```clojure
(println (inc' 5)) ; 6
(println (map inc' '(1 2))) ; (2 3)
```
