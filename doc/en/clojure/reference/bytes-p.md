# bytes?

`(bytes? x)`

`clojure.core/bytes?`: `false` for every value: arrays ignore their element class here, so there is no byte array kind. The argument is still evaluated. As a value a one-argument function.

```clojure
(println (bytes? [1 2]))  ; false
```
