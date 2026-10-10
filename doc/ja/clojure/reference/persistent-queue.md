# PersistentQueue/EMPTY

`clojure.lang.PersistentQueue/EMPTY`

空の永続的な先入れ先出しキューです。`clojure.lang.PersistentQueue` を import すれば
`PersistentQueue/EMPTY` とも書けます。[`conj`](conj.md) と [`into`](into.md) は末尾に加え、
[`peek`](peek.md) は先頭の要素を、[`pop`](pop.md) はそれを除いたキューを返します（空のキューには
`nil` とキュー自身）。`seq` は先頭から末尾へ走査します。キューは sequential なコレクションです。
`=` はベクターやリストと要素ごとに比較し、`hash` はそれらと同じで、`count`・`empty?`・全 seq 操作が
読み、`with-meta` と `meta` はメタデータを保ち、`empty` は空のキューを返します。`instance?` は
`clojure.lang.PersistentQueue` と、オラクルのクラスが実装するインターフェース
（`IPersistentList`、`IPersistentStack`、`java.util.Collection` など）に真で、その `.size`・
`.contains`・`.isEmpty` に答えます。`str` はオラクルと同じく `clojure.lang.PersistentQueue@` に
`hashCode` の16進を続けたものです。クラスは組み込み名前空間の `deftype` で、プログラムが最初に
それを名指した所で読み込まれるので、名指さないプログラムはそのどれも持ちません。

仕様との差異: キューはオラクルの `#object` から同一性ハッシュを除いた形で印字され、`class` は
型のキーワード `:PersistentQueue` を返します。

```clojure
(def q (conj clojure.lang.PersistentQueue/EMPTY 1 2 3))
(println (peek q) (seq (pop q)) (seq (conj q 4))) ; 1 (2 3) (1 2 3 4)
(println (= q [1 2 3]) (count q) (str q)) ; true 3 clojure.lang.PersistentQueue@7861
```
