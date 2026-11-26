# map

`(map f coll...)` / `(map f)`

各入力の seq ビューに関数 `f` を適用した結果を返します。最も短い入力で止まります（オラクルと同様）。リストはそのまま渡され、それ以外のコレクションは先に強制されるため、ベクタ・文字列・マップ（エントリベクタ）・セットに使えます。空の結果は `nil` です。いずれかの入力が lazy の場合、答えは要素ごとに realize される lazy seq です。値としては rest 引数の lambda なので、`map` は `inc` のような素の名前を取れます。

`(map f)` は[トランスデューサー](transducers.md)を返します（値としても同じです）。

```clojure
(println (map inc '(1 2)))      ; (2 3)
(println (map #(inc %) [1 2 3])) ; (2 3 4)
(println (map :k [{:k 1} {:k 2}])) ; (1 2)
(println (map + [1 2 3] [10 20])) ; (11 22)
(println (take 3 (map inc (iterate inc 0)))) ; (1 2 3)
(println (into [] (map inc) [1 2])) ; [2 3]
```
