# aset-boolean

`(aset-boolean array index value)` `(aset-boolean array index index2 ... value)`

Stores `value`, cast as [boolean](boolean.md) casts it (anything truthy counts), at the index of `array`, mutating the array, and answers `value`. The index converts first, like the oracle's reflection converts it before the value. `nil` is the oracle's `NullPointerException` with no message, a [byte array](byte-array.md) its `IllegalArgumentException` (`argument type mismatch`), anything else no vector its `IllegalArgumentException` (`Argument is not an array`), and past either end its `ArrayIndexOutOfBoundsException` with no message. Of more than one index the intermediate levels read like [aget](aget.md).

An array is a vector here: the verb stores into any vector, where the oracle checks the component ([deviations](../deviations.md)). Runs on every backend; as a value a function of three or more arguments.

```clojure
(def asb2 (boolean-array [true false]))
(println (aset-boolean asb2 1 1) (vec asb2)) ; 1 [true true]
```
