# sorted-map

`(sorted-map k v ...)`

`clojure.core/sorted-map`: 与えたペアを `compare` の順に並べたマップです。表示・seq・`keys`/`vals`
の走査はキーの順になります。先にあるキーと比較して等しいキーは、値を置き換え、先のキーを
残します（`1` と `1.0` は同じキーです）。マップの操作はどれもこのマップを受け取り、ソート済み
マップを返します（`assoc`・`dissoc`・`conj`・`merge`・`update`・`into` など）。キーの検索は
`=` ではなく `compare` で行います。`compare` が順序を付けられないキー（リスト・マップ・
セット・関数）はオラクルと同じ `Default comparator requires nil, Number, or Comparable` で、
値のないキーは `No value supplied for key` でシグナルを上げます。同じエントリーのハッシュマップと `=` です。値としては
任意個の引数を取る関数です。

```clojure
(println (sorted-map :b 1 :a 2))       ; {:a 2, :b 1}
(println (assoc (sorted-map :b 1) :a 3)) ; {:a 3, :b 1}
(println (get (sorted-map 1 :one) 1.0)) ; :one
```
