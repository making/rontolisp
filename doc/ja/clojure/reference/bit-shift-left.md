# bit-shift-left

`(bit-shift-left x n)`

`x` を左へ `n` ビットシフトしたもの。64 ビット 2 の補数整数として oracle と同じ。`n` は下位 6 ビットにマスクされるため `(bit-shift-left 1 64)` は `1`、答えはラップされるため `(bit-shift-left 1 63)` は `-9223372036854775808`。64 ビット整数以外は拒否される（`bit operation not supported for: class ...`。`nil` は `NullPointerException`）。値としては 2 引数を取る。

```clojure
(println (bit-shift-left 1 2) (bit-shift-left 1 64) (bit-shift-left 1 63)) ; 4 1 -9223372036854775808
```
