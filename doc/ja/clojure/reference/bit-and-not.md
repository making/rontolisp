# bit-and-not

`(bit-and-not x & ys)`

`x` と `ys` の各要素の補数との AND を 64 ビット 2 の補数整数として取ったもの。oracle と同じ。64 ビット整数以外は拒否される（`bit operation not supported for: class ...`。`nil` は `NullPointerException`）。値としては 2 引数以上を取る。

```clojure
(println (bit-and-not 5 3) (bit-and-not 1 2 3)) ; 4 0
```
