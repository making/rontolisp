# into

`(into to from)` / `(into to xform from)`

Answers `from` conjoined onto `to` one member at a time: lists grow at the
front, vectors at the end, maps take entries, sets members. With a
[transducer](transducers.md) `xform`, `from` steps through it first (the oracle's
`(transduce xform conj to from)`). A lazy `from` pours in whole. As a value two or three
arguments.

```clojure
(println (into [] [1 2])) ; [1 2]
(println (into {} [[:a 1]])) ; {:a 1}
(println (into '() [1 2])) ; (2 1)
(println (into [] (map inc) [1 2])) ; [2 3]
```
