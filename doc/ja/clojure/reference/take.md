# take

`(take n coll)`

`coll` の最初の `n` 要素のリストを返します。lazy 要素を1つずつ辿るため、`(take n infinite)` は strict な prefix で終了します。答えに必要な分だけ realize します。カウントが尽きても次の要素は unrealized のままです。

仕様との差異: 長すぎる take は、空のとき seq 全体として `nil` を返します。オラクルは `()` を表示します。

```clojure
(println (take 3 (range 10))) ; (0 1 2)
(println (take 2 [1 2 3 4]))  ; (1 2)
(println (take 10 [1 2]))     ; (1 2)
(println (take 5 (iterate inc 0))) ; (0 1 2 3 4)
```
