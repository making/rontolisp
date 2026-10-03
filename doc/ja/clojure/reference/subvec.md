# subvec

`(subvec v start)` / `(subvec v start end)`

`clojure.core/subvec`: `v` の `start` から `end`（省略時は `v` の末尾）までの要素を持つ新しい
ベクターを返します。範囲外の境界、`end` を超える `start`、`nil` の境界、ベクターでない `v`
（`nil`、リスト、文字列）は、オラクルの `IndexOutOfBoundsException` と同様にシグナルを上げます。
小数の境界は切り捨てます。結果はビューではなくコピーです。値としては2引数か3引数を取ります。

```clojure
(println (subvec [1 2 3 4] 1 3)) ; [2 3]
(println (subvec [1 2 3 4] 2))   ; [3 4]
```
