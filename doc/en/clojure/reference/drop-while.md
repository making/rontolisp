# drop-while

`(drop-while pred coll)`

Answers `coll`'s seq view past the truthy prefix, sharing the tail. As a value
a two-argument lambda.

```clojure
(println (drop-while neg? [-2 -1 0 1])) ; (0 1)
```
