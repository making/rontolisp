# reduce-kv

`(reduce-kv f init coll)`

マップやレコードのエントリ（テーブルの走査順）またはベクターのインデックスと要素の組に
対して `init` から `(f acc k v)` を畳み込みます。`nil` には `init` を返します。リスト・セット・
文字列はオラクル同様にシグナルします。`reduced` は未対応です。値としては3引数の関数です。

```clojure
(println (reduce-kv (fn [acc k v] (+ acc v)) 0 {:a 1 :b 2})) ; 3
(println (reduce-kv (fn [acc i x] (conj acc [i x])) [] [:x :y])) ; [[0 :x] [1 :y]]
```
