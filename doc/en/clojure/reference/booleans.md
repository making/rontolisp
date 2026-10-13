# booleans

`(booleans xs)`

Casts to `boolean[]`: answers the array `xs` itself -- `nil` itself, a vector narrowed as [boolean-array](boolean-array.md) narrows it, anything else the oracle's `ClassCastException`.

An array is a vector here: a vector narrows where the oracle refuses what is no primitive array ([deviations](../deviations.md)). Runs on every backend; as a value a function of one argument.

```clojure
(prn (vec (booleans (boolean-array [true])))) ; [true]
(prn (booleans nil)) ; nil
```
