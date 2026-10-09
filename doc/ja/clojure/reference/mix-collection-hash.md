# mix-collection-hash

`(mix-collection-hash hash-basis count)`

`clojure.core/mix-collection-hash`: コレクションの要素を合わせたハッシュ `hash-basis` を、
要素数 `count` で Murmur3 の最終混合にかけます。[hash-ordered-coll](hash-ordered-coll.md) と
[hash-unordered-coll](hash-unordered-coll.md) の最後の段階で、自身の `hasheq` を計算する
コレクション型が使います。各引数は `^long` 引数として変換され（double と比は切り捨て、文字・
文字列・`nil` は拒否）、続いて int に変換されます。int の範囲外はオラクルの
`ArithmeticException` です。

```clojure
(prn (mix-collection-hash 1 0))   ; -2017569654
(prn (= (hash [1 2])
        (mix-collection-hash (reduce #(unchecked-add-int (unchecked-multiply-int 31 %1) (hash %2)) 1 [1 2])
                             2)))   ; true
```
