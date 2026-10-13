# bit-clear

`(bit-clear x n)`

`x` のビット `n` を落としたもの。64 ビット 2 の補数整数として oracle と同じ。`n` は下位 6 ビットにマスクされるため `(bit-clear 5 64)` は `4`。64 ビット整数以外は拒否される（`bit operation not supported for: class ...`。`nil` は `NullPointerException`）。値としては 2 引数を取る。

```clojure
(println (bit-clear 7 1) (bit-clear 5 64)) ; 5 4
```
