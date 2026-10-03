# sorted-map-by

`(sorted-map-by comparator k v ...)`

`clojure.core/sorted-map-by`: `comparator` の順に並べたソート済みマップです。`comparator` は
キー2つを受け取る関数で、その答えはオラクルと同じく読みます。数値は整数部の符号で順序を
決め（`compare`、`(- a b)`。`0.5` は等しい扱いです）、`true` は第1引数を先にし、`false` は
引数を入れ替えた呼び出しの結果を見ます（`<`、`>`）。`comparator` が等しいとしたキーは同じ
キーです。どの操作も比較関数を引き継ぎます。キーは `sorted-map` のような検査を受けず、関数で
ない `comparator` はシグナルを上げます。値としては比較関数とペアを取る関数です。

```clojure
(println (sorted-map-by > 1 :a 2 :b 3 :c))         ; {3 :c, 2 :b, 1 :a}
(prn (sorted-map-by #(compare %2 %1) "b" 1 "a" 2)) ; {"b" 1, "a" 2}
```
