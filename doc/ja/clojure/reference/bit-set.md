# bit-set

`(bit-set x n)`

`x` のビット `n` を立てたもの。64 ビット 2 の補数整数として oracle と同じ。`n` は下位 6 ビットにマスクされるため `(bit-set 5 64)` は `5`。64 ビット整数以外は拒否される（`bit operation not supported for: class ...`。`nil` は `NullPointerException`）。値としては 2 引数を取る。

```clojure
(println (bit-set 0 3) (bit-set 5 64)) ; 8 5
```
