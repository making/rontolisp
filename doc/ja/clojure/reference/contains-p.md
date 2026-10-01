# contains?

`(contains? coll k)`

`k` が存在するかどうかを返します。マップのキー、セットの要素、ベクターか文字列の有効なインデックス（範囲チェック）のいずれかです。`nil` は何も含まず、それ以外のものは `false` を返します。

```clojure
(println (contains? {:a 1} :a))  ; true
(println (contains? #{1 2} 9))   ; false
(println (contains? [:a :b] 1))  ; true
(println (contains? "abc" 0))    ; true
(println (contains? nil :a))     ; false
```
