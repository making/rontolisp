# cast

`(cast class x)`

`x` が `class` のインスタンスなら `x`、`nil` には `nil`、それ以外は oracle の `ClassCastException`（`Cannot cast A to B`）。`nil` のクラスは `NullPointerException`、クラスでないものは `ClassCastException`。呼び出しで名前を書いたクラスは `instance?` と同じテストなので、レコード・deftype・基本種別はすべてのバックエンドでキャストできる。値がここに存在しないホストクラスは常に拒否される（ホストクラスは wasm バックエンドに存在しない）。計算で求めたクラスはプロトコルタグ（`class` が答えるもの）で比較し、計算で求めたホストクラスは名前の種類で比較する。値としては 2 引数を取る。

```clojure
(println (cast String "a")) ; a
(println (cast String nil)) ; nil
(println (try (cast Integer "a") (catch ClassCastException e :cce))) ; :cce
```
