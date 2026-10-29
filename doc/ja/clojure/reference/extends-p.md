# extends?

`(extends? protocol type)`

`clojure.core/extends?`: `type` が `protocol` をインライン（`defrecord`/`deftype`）で、または `extend`・`extend-type`・`extend-protocol` で実装していれば `true` を返します。`Object` への拡張はオラクルと同様に `Object` 自身にだけ数えます。2つの名前ならプログラムの降低時に表を読み、`type` には `extend-type` と同じ名前を指定できます。計算されたプロトコルや型（`(class x)` のような式、またはローカル）なら実行時のプロトコル値と型値を読み、値として（`(def e? extends?)` を適用するなど）使う場合も同じです。

```clojure
(defprotocol ExP (ex-m [x]))
(defrecord ExR [a] ExP (ex-m [x] 1))
(defrecord ExS [a])
(println (extends? ExP ExR) (extends? ExP ExS))  ; true false
```
