# *'

`(*' x...)`

The product, with overflow checking. Integers are bignums here, so this is
`*`: it never overflows, and `(*')` is `1`. Anything but a number is refused
with the oracle's class (`NullPointerException` for `nil`, `ClassCastException`
otherwise). As a value any number of arguments.

```clojure
(println (*' 2 3 4)) ; 24
(println (reduce *' [2 3 4])) ; 24
```
