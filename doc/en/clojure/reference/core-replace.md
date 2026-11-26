# replace

`(replace smap coll)` / `(replace smap)`

`clojure.core/replace`: each member of `coll` that `smap` holds is replaced by its
value there. `smap` is a map or record (keys compare by `=`) or a vector (an integer
member in range is an index); `nil` holds nothing, anything else signals. A vector
`coll` answers a vector; anything else answers its seq, lazy for a lazy input and a
strict list otherwise (so `(replace {} nil)` is `nil`, where the oracle prints `()`).
`(replace smap)` is its [transducer](transducers.md). As a value one or two arguments.

```clojure
(println (replace {0 :z} [0 1 0]))      ; [:z 1 :z]
(println (replace [:a :b] [0 1 2]))     ; [:a :b 2]
(println (replace {0 :z} '(0 1 0)))     ; (:z 1 :z)
(println (into [] (replace {1 :a}) [1 2])) ; [:a 2]
```
