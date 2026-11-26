# chars

`(chars xs)`

Casts to `char[]`: answers the array `xs` itself -- `nil` itself, a vector narrowed as [char-array](char-array.md) narrows it, anything else the oracle's `ClassCastException`.

An array is a vector here: a vector narrows where the oracle refuses what is no primitive array ([deviations](../deviations.md)). Runs on every backend; as a value a function of one argument.

```clojure
(prn (mapv int (chars (char-array [\a])))) ; [97]
(prn (chars nil)) ; nil
```
