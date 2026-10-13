# doubles

`(doubles xs)`

Casts to `double[]`: answers the array `xs` itself -- `nil` itself, a vector widened as [double-array](double-array.md) widens it, anything else the oracle's `ClassCastException`.

An array is a vector here: a vector widens where the oracle refuses what is no primitive array ([deviations](../deviations.md)). Runs on every backend; as a value a function of one argument.

```clojure
(prn (vec (doubles (double-array [1.0 2.0])))) ; [1.0 2.0]
(prn (doubles nil)) ; nil
```
