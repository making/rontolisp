# object-array

`(object-array size-or-seq)`

Answers an object array: of a number, that many `nil`s, else the members of a seq,
standing as is. An array is a vector here, as one `make-array` makes: it reads through
[aget](aget.md), writes through [aset](aset.md), measures through [alength](alength.md),
copies through [aclone](aclone.md), and takes every seq verb, `count`, `nth`, `get` and
`=` as a vector does. `class` answers `:vector`, `str` and `pr` print the vector, and
`=` compares elements, where the oracle answers its classes, prints `#object` and
compares by identity ([deviations](../deviations.md)). Runs on every backend; as a value
a one-argument function.

```clojure
(prn (vec (object-array [1 "a" nil])) (vec (object-array 2))) ; [1 "a" nil] [nil nil]
```
