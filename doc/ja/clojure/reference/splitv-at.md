# splitv-at

`(splitv-at n coll)`

取った側をベクターにした `split-at`。`[(vec (take n coll)) (drop n coll)]`。値としては 2 引数を取る。

```clojure
(println (splitv-at 2 [1 2 3 4])) ; [[1 2] (3 4)]
```
