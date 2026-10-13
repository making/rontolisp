# -'

`(-' x...)`

The difference, with overflow checking. Integers are bignums here, so this is
`-`: it never overflows. Of one argument the negation, of none the oracle's
arity error. Anything but a number is refused with the oracle's class
(`NullPointerException` for `nil`, `ClassCastException` otherwise). As a value
one or more arguments.

```clojure
(println (-' 10 1 2)) ; 7
(println (-' 5)) ; -5
```
