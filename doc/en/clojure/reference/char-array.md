# char-array

`(char-array size-or-seq)` `(char-array size init-val-or-seq)`

Answers a char array: of a number, that many nulls, else the members of a seq, each a
character. With `size` and an init, every element is the character `init`, or the next
member of the seq `init` (nulls past its end).

An array is a vector here, as one `make-array` makes: it reads through [aget](aget.md),
writes through [aset](aset.md) (which stores any value, where the oracle's reflection
refuses one of another type), measures through [alength](alength.md), copies through
[aclone](aclone.md), and takes every seq verb, `count`, `nth`, `get` and `=` as a vector
does. `class` answers `:vector`, `str` and `pr` print the vector, and `=` compares
elements, where the oracle answers its classes, prints `#object` and compares by
identity ([deviations](../deviations.md)). Runs on every backend; as a value a function
of one or two arguments.

```clojure
(prn (vec (char-array [\a \b])) (vec (char-array 2 \z))) ; [\a \b] [\z \z]
```
