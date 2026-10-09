# aset-byte

`(aset-byte array index value)`

Stores `value` at `index` of the byte array `array`, cast as [byte](byte.md) casts it (a number
outside `-128` to `127` is the oracle's `IllegalArgumentException`), and answers `value`. Any
other array is the oracle's refusal. As a value a function of three arguments.

```clojure
(def asb (byte-array 2))
(println (aset-byte asb 0 -56) (vec asb)) ; -56 [-56 0]
```
