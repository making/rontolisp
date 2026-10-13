# unsigned-bit-shift-right

`(unsigned-bit-shift-right x n)`

`x` を符号なしとして右へ `n` ビット論理シフトしたもの。oracle と同じ。`n` は下位 6 ビットにマスクされ、答えは 64 ビットにラップされるため `(unsigned-bit-shift-right -1 0)` は `-1`。64 ビット整数以外は拒否される（`bit operation not supported for: class ...`。`nil` は `NullPointerException`）。値としては 2 引数を取る。

```clojure
(println (unsigned-bit-shift-right -8 2) (unsigned-bit-shift-right -1 0)) ; 4611686018427387902 -1
```
