# floats

`(floats xs)`

Casts to `float[]`: answers the array `xs` itself -- `nil` itself, a vector widened as [float-array](float-array.md) widens it, anything else the oracle's `ClassCastException`.

An array is a vector here: a vector widens where the oracle refuses what is no primitive array ([deviations](../deviations.md)). Runs on every backend; as a value a function of one argument.

```clojure
(prn (vec (floats (float-array [1.0])))) ; [1.0]
(prn (floats nil)) ; nil
```
