# tree-seq

`(tree-seq branch? children root)`

Answers the nodes of the tree under `root`, depth first, as a lazy seq: a node is
followed by the nodes under each of `(children node)` when `(branch? node)` is truthy.
`branch?` and `children` are called on a node as its cell realizes, like the oracle's
walk, so an endless tree takes. Also names a function value.

```clojure
(println (tree-seq vector? seq [1 [2 [3]] 4])) ; ([1 [2 [3]] 4] 1 [2 [3]] 2 [3] 3 4)
(println (map :n (tree-seq :kids :kids {:n 1 :kids [{:n 2} {:n 3}]}))) ; (1 2 3)
(println (take 4 (tree-seq (constantly true) (fn [n] [(* 2 n) (inc (* 2 n))]) 1))) ; (1 2 4 8)
```
