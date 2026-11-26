# create-struct

`(create-struct key...)`

The struct definition over the keys: the key vector `struct` and `struct-map`
read, like the oracle's definition object (which prints as its key vector
here). Any key goes; of none the oracle's `IllegalArgumentException` (`Must
supply keys`). As a value any number of arguments but none.

```clojure
(println (struct (create-struct :x :y) 1 2)) ; {:x 1, :y 2}
```
