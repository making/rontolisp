# bytes?

`(bytes? x)`

`clojure.core/bytes?`: `true` for a byte array (what [byte-array](byte-array.md), `.getBytes`
and the byte readers answer), `false` for anything else. As a value a one-argument function.

```clojure
(println (bytes? (byte-array 2)) (bytes? [1 2]))  ; true false
```
