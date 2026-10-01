# nth

`(nth coll i)` / `(nth coll i default)`

`coll` の seq ビューの `i` 番目の要素を返します。末尾を超えたとき、3 アリティは `default` を、2 アリティは `nil` を返します。

Deviation: 末尾を超えた `nth` は default を返します（省略時は nil）。oracle は例外を投げます。VALUE としての `nth` は `(collection index)` のラムダです -- 裸の基盤の `nth` がインデックスを先に取るため、Clojure 順になります。

```clojure
(println (nth [10 20 30] 1))    ; 20
(println (nth [10 20] 5 :nf))   ; :nf
(println (map (fn [v] (nth v 0)) [[1 2] [3 4]])) ; (1 3)
```
