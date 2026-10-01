# defstruct

`(defstruct name key...)`

キーベクターを名前の裏に保持し、`struct`/`struct-map` がちょうどそのキーを持つマップを組み立てます。キーはオラクル同様キーワードです（struct マップの印字はここの単一エントリマップと同じ形です）。

```clojure
(defstruct account :id :balance)
(println (:id (struct account 1 100))) ; 1
```
