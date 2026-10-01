# re-matches

`(re-matches pattern s)`

`pattern` が `s` 全体にマッチするときマッチを返し、そうでなければ `nil` を返します。`re-find` 形です（グループありではベクター）。関数値としても動きます。

```clojure
(println (re-matches #"a+" "aaa")) ; aaa
(println (re-matches #"a+" "aaab")) ; nil
```
