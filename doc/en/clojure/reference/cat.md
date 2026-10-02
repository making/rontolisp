# cat

`cat`

A [transducer](transducers.md) itself: it steps each input's members in turn.
`(mapcat f)` is `(comp (map f) cat)`.

```clojure
(println (into [] cat [[1 2] [3]])) ; [1 2 3]
(println (into [] (comp (map range) cat) [2 3])) ; [0 1 0 1 2]
```
