# rand-int

`(rand-int bound)`

プログラム所有の生成器による1回のスケール済み抽選の切り捨てです。正の bound には
`[0,bound)` の整数で答えます。`0` には `0` で、負の bound には負で答えます
（oracle の int-of-rand と同様であり、ドメインチェックはありません）。値としては
1引数ラムダです。

```clojure
(println (rand-int 1)) ; 0
(println (contains? #{0 1 2} (rand-int 3))) ; true
```
