# longs

`(longs xs)`

Casts to `long[]`: answers the array `xs` itself -- `nil` itself, a vector narrowed as [long-array](long-array.md) narrows it, anything else the oracle's `ClassCastException`.

An array is a vector here: a vector narrows where the oracle refuses what is no primitive array ([deviations](../deviations.md)). Runs on every backend; as a value a function of one argument.

```clojure
(prn (vec (longs (long-array [1 2])))) ; [1 2]
(prn (longs nil)) ; nil
```
