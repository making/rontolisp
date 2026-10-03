# run!

`(run! proc coll)`

`coll` の各要素に `proc` を効果のために呼び出し、`nil` を答えます。lazy seq は要素ごとに
realize されます。関数値にもなり、`println`・`print`・`prn`・`pr` も同様です。

```clojure
(run! println [1 2]) ; 1 と 2 を出力
(println (run! inc [1 2])) ; nil
```
