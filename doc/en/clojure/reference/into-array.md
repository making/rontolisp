# into-array

`(into-array aseq)` `(into-array type aseq)`

Answers an array holding the members of `aseq`. Without `type` the component is the
first member's class (`Object` for no first member, or a `nil` one), and every other
member must be of it. With `type` -- a class name, or `Integer/TYPE` and the other
primitive classes -- a primitive class casts each member (an `int` truncates and refuses
past its range, a `long` takes the integers and characters, a `double` those and the
doubles, a `char` the numbers in its range, a `boolean` the booleans), and any other
class checks it (`Object` takes all, `Number` the numbers, `CharSequence` the strings).
A mismatch is the oracle's `IllegalArgumentException` (`array element type mismatch`,
or `argument type mismatch` for a primitive unboxing).

An array is a vector here, as one `make-array` makes: it reads through [aget](aget.md),
writes through [aset](aset.md), measures through [alength](alength.md), copies through
[aclone](aclone.md), and takes every seq verb, `count`, `nth`, `get` and `=` as a vector
does. `class` answers `:vector`, `str` and `pr` print the vector, and `=` compares
elements, where the oracle answers its classes, prints `#object` and compares by
identity; a small `N` literal is a `Long` here, so it takes a `Long` component where the
oracle's `BigInt` refuses, and a computed `Class` object is the oracle's
`ClassCastException` ([deviations](../deviations.md)). Runs on every backend; as a value
a function of one or two arguments.

```clojure
(prn (vec (into-array [1 2])) (vec (into-array ["a"]))) ; [1 2] ["a"]
(prn (vec (into-array String ["a"])) (vec (into-array Integer/TYPE [1.9]))) ; ["a"] [1]
```
