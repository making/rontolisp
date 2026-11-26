# +'

`(+' x...)`

オーバーフローチェック付きの合計。整数はここでは多倍長整数なので `+` と同じで、オーバーフローせず、`(+')` は `0`。数値以外は oracle と同じクラスで拒否される（`nil` は `NullPointerException`、それ以外は `ClassCastException`）。値としては任意個の引数を取る。

```clojure
(println (+' 1 2 3)) ; 6
(println (reduce +' [1 2 3])) ; 6
```
