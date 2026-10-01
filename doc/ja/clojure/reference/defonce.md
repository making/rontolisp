# defonce

`(defonce name init?)`

名前がまだ束縛されていない場合の `def` です。最初の評価が勝つため、`def` がリセットする場面でもリロードでルートが保たれます。

```clojure
(defonce words ["a" "b"])
(defonce words ["c"])
(println words) ; [a b]
```
