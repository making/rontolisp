# shorts

`(shorts xs)`

Casts to `short[]`: answers the array `xs` itself -- `nil` itself, a vector narrowed as [short-array](short-array.md) narrows it, anything else the oracle's `ClassCastException`.

An array is a vector here: a vector narrows where the oracle refuses what is no primitive array ([deviations](../deviations.md)). Runs on every backend; as a value a function of one argument.

```clojure
(prn (vec (shorts (short-array [1])))) ; [1]
(prn (shorts nil)) ; nil
```
