# tree-seq

`(tree-seq branch? children root)`

`root` 以下の木のノードを深さ優先の遅延シーケンスで返します。`(branch? node)` が truthy なら、
そのノードの後に `(children node)` の各要素以下のノードが続きます。`branch?` と `children` は、
オラクルの走査と同じくノードのセルが実体化するときに呼ばれるため、無限の木でも使えます。関数値
としても使えます。

```clojure
(println (tree-seq vector? seq [1 [2 [3]] 4])) ; ([1 [2 [3]] 4] 1 [2 [3]] 2 [3] 3 4)
(println (map :n (tree-seq :kids :kids {:n 1 :kids [{:n 2} {:n 3}]}))) ; (1 2 3)
(println (take 4 (tree-seq (constantly true) (fn [n] [(* 2 n) (inc (* 2 n))]) 1))) ; (1 2 4 8)
```
