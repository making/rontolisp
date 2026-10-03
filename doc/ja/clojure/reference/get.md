# get

`(get m k)` / `(get m k default)`

`k` の下の値を返します。マップかセットでは（メンバーそのもの）、ベクターのインデックス `k` の要素か文字列の文字、見つからなければ `default`（省略時は nil）です。`nil` も読め、リストは default を返します。キーワード呼び出し位置 `(:k m)` も同じ読み取りが支えています。record はエントリ表を通して読み、deftype/reify はオラクル同様 default を返します。

値としては 2 引数・3 引数の読み取りです。

キーは `=` で比較するので、ベクター・リスト・マップ・セットのキーも等しいキーを見つけます。

```clojure
(println (get {:a 1} :a))      ; 1
(println (get {:a 1} :b :dflt)) ; :dflt
(println (get [10 20 30] 1))   ; 20
(println (get "abc" 1))        ; b
(println (get #{1 2} 2))       ; 2
(println (get {[:a] 1} [:a]))  ; 1
(println (get nil :a :d))      ; :d
(println (map get [{:a 1}] [:a])) ; (1)
```
