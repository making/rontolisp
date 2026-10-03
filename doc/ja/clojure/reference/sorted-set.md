# sorted-set

`(sorted-set x ...)`

`clojure.core/sorted-set`: 与えた要素を `compare` の順に並べたセットです。表示と seq は順序
どおりになります。先にある要素と比較して等しい要素は捨て、先の要素を残します。セットの操作は
どれもこのセットを受け取り、ソート済みセットを返します（`conj`・`disj`・`into`、要素を加える
`clojure.set/union` など）。所属は `compare` で判定するので、`(contains? (sorted-set 1) 1.0)`
は `true` です。`compare` が順序を付けられない要素は、オラクルの `Default comparator
requires nil, Number, or Comparable` でシグナルを上げます。同じ要素のハッシュセットと `=`
です。値としては任意個の引数を取る関数です。

```clojure
(println (sorted-set 3 1 2))           ; #{1 2 3}
(println (conj (sorted-set 3 1) 2))    ; #{1 2 3}
(println (first (sorted-set "b" "a"))) ; a
```
