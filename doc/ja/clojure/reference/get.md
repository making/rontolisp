# get

`(get m k)` / `(get m k default)`

`k` の下の値を返します。マップかセットでは（メンバーそのもの）、ベクターのインデックス `k` の要素か文字列の文字、見つからなければ `default`（省略時は nil）です。`nil` も読め、リストは default を返します。キーワード呼び出し位置 `(:k m)` も同じ読み取りが支えています。

仕様との差異: ベクターと表のキーは構造的ではなく同一性で比較されるため、`(get {[:a] 1} [:a])` はここでは見つからず、オラクルでは `1` を返します。リスト・文字列・数・キーワードは構造的にキーになります。

```clojure
(println (get {:a 1} :a))      ; 1
(println (get {:a 1} :b :dflt)) ; :dflt
(println (get [10 20 30] 1))   ; 20
(println (get "abc" 1))        ; b
(println (get #{1 2} 2))       ; 2
(println (get nil :a :d))      ; :d
```
