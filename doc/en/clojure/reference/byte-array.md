# byte-array

`(byte-array size-or-seq)` `(byte-array size init)`

Answers a byte array, Java's `byte[]`: `size` zeros, or the members of a seq, each stored as
`Number.byteValue` stores it (an integer's low eight bits, so `300` is `44`). With `size` and
`init`, every element is the byte `init`, or the next member of the seq `init` (zeros past its
end). An element reads back as a signed byte, `-128` to `127`. The array is mutable
([aset](aset.md), [aset-byte](aset-byte.md)); it is no collection (`coll?` and `vector?` are
false, `=` is identity) but seqs its elements, so `count`, `seq`, `vec`, `nth`, `get` and the
seq functions take it. It prints as the oracle's `#object["[B" ...]` without the identity hash.
As a value a function of one or two arguments.

```clojure
(def ba (byte-array [104 105 -1 300]))
(println (vec ba) (alength ba) (count ba)) ; [104 105 -1 44] 4 4
(println (vec (byte-array 3)) (vec (byte-array 3 [1 2]))) ; [0 0 0] [1 2 0]
(println (String. (byte-array [104 105]) "UTF-8")) ; hi
```
