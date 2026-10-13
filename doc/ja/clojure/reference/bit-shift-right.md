# bit-shift-right

`(bit-shift-right x n)`

`x` を右へ `n` ビット算術シフトしたもの。64 ビット 2 の補数整数として oracle と同じ。`n` は下位 6 ビットにマスクされるため `(bit-shift-right 8 64)` は `8`。64 ビット整数以外は拒否される（`bit operation not supported for: class ...`。`nil` は `NullPointerException`）。値としては 2 引数を取る。

```clojure
(println (bit-shift-right -8 2) (bit-shift-right 8 64)) ; -2 8
```
