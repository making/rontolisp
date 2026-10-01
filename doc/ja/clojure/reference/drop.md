# drop

`(drop n coll)`

`coll` の seq ビューから最初の `n` 要素を除いたものを、ビューへの `nthcdr` で計算して返します。strict です。コレクションより長い drop は空の seq を返します。

仕様との差異: 長すぎる drop は `nil` を返します。オラクルは `()` を表示します。

```clojure
(println (drop 2 [1 2 3 4]))  ; (3 4)
(println (drop 8 (range 10))) ; (8 9)
(println (drop 10 [1 2]))     ; nil
```
