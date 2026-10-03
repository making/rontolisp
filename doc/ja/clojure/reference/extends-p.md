# extends?

`(extends? protocol type)`

`clojure.core/extends?`: `type` が `protocol` をインライン（`defrecord`/`deftype`）で、または `extend`・`extend-type`・`extend-protocol` で実装していれば `true` を返します。`Object` への拡張はオラクルと同様に `Object` 自身にだけ数えます。どちらも `satisfies?` のプロトコルと同じくリテラルの名前で、`type` には `extend-type` と同じ名前を指定できます。数値のクラスは1つの行を共有します（`Long` も `Double` も同じ）。値としての形はありません。

```clojure
(defprotocol ExP (ex-m [x]))
(defrecord ExR [a] ExP (ex-m [x] 1))
(defrecord ExS [a])
(println (extends? ExP ExR) (extends? ExP ExS))  ; true false
```
