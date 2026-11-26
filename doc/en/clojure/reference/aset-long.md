# aset-long

`(aset-long array index value)` `(aset-long array index index2 ... value)`

Stores `value`, cast as [long](long.md) casts it, at the index of `array`, mutating the array, and answers `value`. The index converts first, like the oracle's reflection converts it before the value. `nil` is the oracle's `NullPointerException` with no message, a [byte array](byte-array.md) its `IllegalArgumentException` (`argument type mismatch`), anything else no vector its `IllegalArgumentException` (`Argument is not an array`), and past either end its `ArrayIndexOutOfBoundsException` with no message. Of more than one index the intermediate levels read like [aget](aget.md).

An array is a vector here: the verb stores into any vector, where the oracle checks the component ([deviations](../deviations.md)). Runs on every backend; as a value a function of three or more arguments.

```clojure
(def asl (long-array [1 2]))
(println (aset-long asl 0 9) (vec asl)) ; 9 [9 2]
```
