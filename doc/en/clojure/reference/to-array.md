# to-array

`(to-array coll)`

Answers an object array holding the contents of `coll`. An array is a vector here, as
one `make-array` makes: it reads through [aget](aget.md), writes through
[aset](aset.md), measures through [alength](alength.md), copies through
[aclone](aclone.md), and takes every seq verb, `count`, `nth`, `get` and `=` as a vector
does. `class` answers `:vector`, `str` and `pr` print the vector, and `=` compares
elements, where the oracle answers its classes, prints `#object` and compares by
identity ([deviations](../deviations.md)). A value that is no seqable is the oracle's
`RuntimeException` (`Unable to convert: ... to Object[]`). Runs on every backend; as a
value a one-argument function.

```clojure
(prn (vec (to-array [1 "a" nil])) (vec (to-array nil))) ; [1 "a" nil] []
```
