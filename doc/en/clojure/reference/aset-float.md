# aset-float

`(aset-float array index value)` `(aset-float array index index2 ... value)`

Stores `value`, cast as [float](float.md) casts it, at the index of `array`, mutating the array, and answers `value`. The index converts first, like the oracle's reflection converts it before the value. `nil` is the oracle's `NullPointerException` with no message, a [byte array](byte-array.md) its `IllegalArgumentException` (`argument type mismatch`), anything else no vector its `IllegalArgumentException` (`Argument is not an array`), and past either end its `ArrayIndexOutOfBoundsException` with no message. Of more than one index the intermediate levels read like [aget](aget.md).

An array is a vector here: the verb stores into any vector, where the oracle checks the component ([deviations](../deviations.md)). Runs on every backend; as a value a function of three or more arguments.

```clojure
(def asf (float-array [1.0 2.0]))
(println (aset-float asf 0 9) (vec asf)) ; 9 [9.0 2.0]
```
