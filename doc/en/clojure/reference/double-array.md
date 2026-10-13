# double-array

`(double-array size-or-seq)` `(double-array size init-val-or-seq)`

Answers a double array: of a number, that many zeros, else the members of a seq, each
widened to a double. With `size` and an init, every element is the double `init` (any
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
(println (vec (double-array [1.5 2])) (vec (double-array [1/3]))) ; [1.5 2.0] [0.3333333333333333]
```
