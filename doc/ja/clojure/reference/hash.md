# hash

`(hash x)`

`clojure.core/hash`: `=` と整合するハッシュで、オラクルの `Util.hasheq` と同じ数を返します。
long、文字列、キーワードやシンボルの名前と名前空間、コレクションの要素（ベクター・リスト・seq
は順に、マップとセットは順不同）に Murmur3 を適用して要素数と混ぜます。double・比・文字・
真偽値・UUID・インスタントは Java の `hashCode` で（0.0 と -0.0 はどちらも `0`）、レコードは
オラクルが生成する `hasheq` です。deftype と reify は `IHashEq` の `hasheq`、なければ上書きした
`hashCode` で答え、どちらもなければ関数や atom と同じく同一性で答えます。同一性のハッシュは
オブジェクトが生きている間は変わりませんが、オラクルの数とは一致しません。値としては1引数の
関数です。

```clojure
(prn (hash 1))                                     ; 1392991556
(prn (hash "a") (hash :a))                         ; 1455541201 -2123407586
(prn (= (hash [1 2]) (hash '(1 2))))               ; true
(prn (= (hash {:a 1}) (hash (sorted-map :a 1))))   ; true
```
