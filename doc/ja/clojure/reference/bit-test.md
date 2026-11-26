# bit-test

`(bit-test x n)`

`x` のビット `n` が立っているかどうか。oracle と同じ。`n` は下位 6 ビットにマスクされるため `(bit-test 5 64)` は true。64 ビット整数以外は拒否される（`bit operation not supported for: class ...`。`nil` は `NullPointerException`）。値としては 2 引数を取る。

```clojure
(println (bit-test 5 0) (bit-test 5 1)) ; true false
```
