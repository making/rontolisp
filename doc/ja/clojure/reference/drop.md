# drop

`(drop n coll)` / `(drop n)`

`coll` の最初の `n` 要素を除いた残りを返します。lazy 要素を1つずつ辿ります。入力が lazy の場合、残りは lazy のままになることがあります。

仕様との差異: 長すぎる drop は `nil` を返します。オラクルは `()` を表示します。

`(drop n)` は[トランスデューサー](transducers.md)を返します（値としても同じです）。

```clojure
(println (drop 2 [1 2 3 4]))  ; (3 4)
(println (drop 8 (range 10))) ; (8 9)
(println (drop 10 [1 2]))     ; nil
(println (take 3 (drop 2 (iterate inc 0)))) ; (2 3 4)
(println (into [] (drop 1) [1 2 3])) ; [2 3]
```
