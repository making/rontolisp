# dec'

`(dec' x)`

オーバーフローチェック付きの -1。整数はここでは多倍長整数なので `dec` と同じで、オーバーフローしない。数値以外は oracle と同じクラスで拒否される（`nil` は `NullPointerException`、それ以外は `ClassCastException`）。値としては 1 引数を取る。

```clojure
(println (dec' 5)) ; 4
(println (map dec' '(1 2))) ; (0 1)
```
