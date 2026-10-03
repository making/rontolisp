# replace

`(replace smap coll)` / `(replace smap)`

`clojure.core/replace`: `coll` の要素のうち `smap` が持つものを、そこでの値に置き換えます。
`smap` はマップか record（キーは `=` で比較）またはベクター（範囲内の整数要素が添字）です。
`nil` は何も持たず、それ以外はシグナルを上げます。ベクターの `coll` にはベクターを返し、それ以外には
その seq を返します。lazy な入力には lazy seq、それ以外には strict なリストです（そのため
`(replace {} nil)` は `nil` で、オラクルは `()` と表示します）。`(replace smap)` は
[トランスデューサー](transducers.md)です。値としては1引数か2引数を取ります。

```clojure
(println (replace {0 :z} [0 1 0]))      ; [:z 1 :z]
(println (replace [:a :b] [0 1 2]))     ; [:a :b 2]
(println (replace {0 :z} '(0 1 0)))     ; (:z 1 :z)
(println (into [] (replace {1 :a}) [1 2])) ; [:a 2]
```
