# to-array-2d

`(to-array-2d coll)`

Answers an array holding one row per member of `coll`, each row the [to-array](to-array.md) of the member. `coll` is any collection of collections; `nil` is the oracle's `NullPointerException`, and a string, a map or anything else no collection its `ClassCastException`.

An array is a vector here, as one `make-array` makes: the answer is a vector of vectors, reading through [aget](aget.md), writing through [aset](aset.md), measuring through [alength](alength.md) and copying through [aclone](aclone.md) ([deviations](../deviations.md)). Runs on every backend; as a value a function of one argument.

```clojure
(prn (mapv vec (to-array-2d [[1 2] [3]]))) ; [[1 2] [3]]
```
