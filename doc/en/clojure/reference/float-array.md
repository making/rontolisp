# float-array

`(float-array size-or-seq)` `(float-array size init-val-or-seq)`

Answers a float array: of a number, that many zeros, else the members of a seq, each
widened to a double -- a float holds a double here, like `float`, so `1/3` prints long
where the oracle's prints `0.33333334`. With `size` and an init, every element is the
float `init` (any number), or the next member of the seq `init` (zeros past its end).

An array is a vector here, as one `make-array` makes: it reads through [aget](aget.md),
writes through [aset](aset.md) (which stores any value, where the oracle's reflection
refuses one of another type), measures through [alength](alength.md), copies through
[aclone](aclone.md), and takes every seq verb, `count`, `nth`, `get` and `=` as a vector
does. `class` answers `:vector`, `str` and `pr` print the vector, and `=` compares
elements, where the oracle answers its classes, prints `#object` and compares by
identity ([deviations](../deviations.md)). Runs on every backend; as a value a function
of one or two arguments.

```clojure
(println (vec (float-array [1 2])) (vec (float-array 2 1.5))) ; [1.0 2.0] [1.5 1.5]
```
