# short-array

`(short-array size-or-seq)` `(short-array size init-val-or-seq)`

Answers a short array: of a number, that many zeros, else the members of a seq, each
narrowed like Java's `(short)` of its `intValue` (so `100000` is `-31072`). With `size`
and an init, every element is the short `init` -- an integer in the short range (a
`Short` is an integer here, where the oracle fills only for one) -- or the next member
of the seq `init` (zeros past its end).

An array is a vector here, as one `make-array` makes: it reads through [aget](aget.md),
writes through [aset](aset.md) (which stores any value, where the oracle's reflection
refuses one of another type), measures through [alength](alength.md), copies through
[aclone](aclone.md), and takes every seq verb, `count`, `nth`, `get` and `=` as a vector
does. `class` answers `:vector`, `str` and `pr` print the vector, and `=` compares
elements, where the oracle answers its classes, prints `#object` and compares by
identity ([deviations](../deviations.md)). Runs on every backend; as a value a function
of one or two arguments.

```clojure
(println (vec (short-array [1 2])) (vec (short-array [100000]))) ; [1 2] [-31072]
```
