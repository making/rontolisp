# hash-ordered-coll

`(hash-ordered-coll coll)`

`clojure.core/hash-ordered-coll`: 順序のあるコレクションについて `=` と整合するハッシュを、
任意の `Iterable`（ベクター・リスト・seq・マップ・セット・レコード・ソート済みコレクション、
`Iterable` を実装した型、Java のコレクション）から求めます。1 から始めて、それまでのハッシュの
31 倍に各要素の `hash` を足し、要素数と混ぜるので、同じ要素のベクターの `hash` と一致します。
マップの要素はエントリーです。`nil` はオラクルの `NullPointerException`、`Iterable` でない値
（文字列・数値・キーワード）は `ClassCastException` です。

```clojure
(prn (hash-ordered-coll [1 2]))                     ; 156247261
(prn (= (hash-ordered-coll '(1 2)) (hash [1 2])))   ; true
```
