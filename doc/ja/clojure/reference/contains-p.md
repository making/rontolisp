# contains?

`(contains? coll k)`

`k` が存在するかどうかを返します。マップのキー、セットの要素、ベクターか文字列の有効なインデックス（範囲チェック）のいずれかです。`nil` は何も含みません。Java の `Map` と `Set` は `get` と同じく `k` を自身で探し、それ以外の Java オブジェクトはオラクルと同様にシグナルします。それ以外のもの（リスト、seq、キーワード、数、`false`）はオラクルと同様に `IllegalArgumentException` をシグナルします。

仕様との差異: 文字列は整数のインデックスでないキーに `false` を返します。オラクルは数を切り捨て、それ以外のキーではシグナルします。

値としては 2 引数のラムダです。

```clojure
(println (contains? {:a 1} :a))  ; true
(println (contains? #{1 2} 9))   ; false
(println (contains? [:a :b] 1))  ; true
(println (contains? "abc" 0))    ; true
(println (contains? nil :a))     ; false
(println (map contains? [#{1}] [1])) ; (true)
```
