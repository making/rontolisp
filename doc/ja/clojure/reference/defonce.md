# defonce

`(defonce name init?)`

名前がまだ束縛されていない場合の `def` です。最初の評価が勝つため、`def` がリセットする場面でもリロードでルートが保たれます。未束縛の var（`declare`、値なしの `def`）は束縛されていないので、`defonce` が束縛します。

```clojure
(defonce words ["a" "b"])
(defonce words ["c"])
(println words) ; [a b]
```
