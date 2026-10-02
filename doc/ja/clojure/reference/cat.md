# cat

`cat`

それ自体が[トランスデューサー](transducers.md)で、各入力の要素を順に流します。
`(mapcat f)` は `(comp (map f) cat)` です。

```clojure
(println (into [] cat [[1 2] [3]])) ; [1 2 3]
(println (into [] (comp (map range) cat) [2 3])) ; [0 1 0 1 2]
```
