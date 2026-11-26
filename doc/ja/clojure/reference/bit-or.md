# bit-or

`(bit-or x y & more)`

オペランドを 64 ビット 2 の補数整数として OR したもの。oracle と同じ。64 ビット整数以外は拒否される（`bit operation not supported for: class ...`。`nil` は `NullPointerException`）。値としては 2 引数以上を取る。

```clojure
(println (bit-or 5 3) (bit-or 0 0)) ; 7 0
```
