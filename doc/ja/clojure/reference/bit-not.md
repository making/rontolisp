# bit-not

`(bit-not x)`

`x` の補数を 64 ビット 2 の補数整数として取ったもの。oracle と同じ。64 ビット整数以外は拒否される（`bit operation not supported for: class ...`。`nil` は `NullPointerException`）。値としては 1 引数を取る。

```clojure
(println (bit-not 5) (bit-not 0)) ; -6 -1
```
