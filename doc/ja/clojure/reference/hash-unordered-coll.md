# hash-unordered-coll

`(hash-unordered-coll coll)`

`clojure.core/hash-unordered-coll`: 順序のないコレクションについて `=` と整合するハッシュを、
任意の `Iterable` から求めます。要素の `hash` の和を要素数と混ぜるので、同じ要素のセットの
`hash`（要素がエントリーならマップの `hash`）と一致します。マップやセットのインタフェースを
実装した型は、これで自身の `hasheq` を答えます。拒否は
[hash-ordered-coll](hash-ordered-coll.md) と同じです。

```clojure
(prn (hash-unordered-coll [1 2]))                     ; 460223544
(prn (= (hash-unordered-coll [2 1]) (hash #{1 2})))   ; true
```
