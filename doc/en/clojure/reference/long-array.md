# long-array

`(long-array size-or-seq)` `(long-array size init-val-or-seq)`

Answers a long array: of a number, that many zeros, else the members of a seq, each
narrowed like Java's `(long)` (an integer's low 64 bits, a double or ratio truncated
inside the long range). With `size` and an init, every element is the long `init` (any
number), or the next member of the seq `init` (zeros past its end).

An array is a vector here, as one `make-array` makes: it reads through [aget](aget.md),
writes through [aset](aset.md) (which stores any value, where the oracle's reflection
refuses one of another type), measures through [alength](alength.md), copies through
[aclone](aclone.md), and takes every seq verb, `count`, `nth`, `get` and `=` as a vector
does. `class` answers `:vector`, `str` and `pr` print the vector, and `=` compares
elements, where the oracle answers its classes, prints `#object` and compares by
identity ([deviations](../deviations.md)). Runs on every backend; as a value a function
of one or two arguments.

```clojure
(println (vec (long-array [1 2])) (vec (long-array [1.9 1e20]))) ; [1 2] [1 9223372036854775807]
```
