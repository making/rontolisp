# sequence

`(sequence coll)` / `(sequence xform coll...)`

コレクション1つにはその seq を返します（lazy seq はそのまま）。[トランスデューサー](transducers.md)
を渡すと、コレクションを1要素ずつそれへ通します。複数のコレクションは最も短いものまで並んで
進み、各行をステップの引数に展開します（複数を取るのは `map` だけです）。lazy な入力には
lazy seq を返すため、無限の入力への `take` も終わります。strict な入力には strict なリストを
返します。空の結果は `nil` で、オラクルは `()` を表示します。値としては1引数以上を取ります。

```clojure
(println (sequence (map inc) [1 2 3])) ; (2 3 4)
(println (take 3 (sequence (map inc) (iterate inc 0)))) ; (1 2 3)
(println (sequence (map vector) [1 2] [3 4])) ; ([1 3] [2 4])
```
