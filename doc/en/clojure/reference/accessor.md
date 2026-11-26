# accessor

`(accessor struct key)`

The function reading `key` out of a struct map of `struct`, like the oracle's.
Of anything but a struct definition its `ClassCastException`, of a key the
definition lacks its `IllegalArgumentException` (`Not a key of struct`). As a
value two arguments.

```clojure
(defstruct planet :name :moons)
(println ((accessor planet :moons) (struct planet "Mars" 2))) ; 2
```
