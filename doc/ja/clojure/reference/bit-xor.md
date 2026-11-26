# bit-xor

`(bit-xor x y & more)`

オペランドを 64 ビット 2 の補数整数として XOR したもの。oracle と同じ。64 ビット整数以外は拒否される（`bit operation not supported for: class ...`。`nil` は `NullPointerException`）。値としては 2 引数以上を取る。

```clojure
(println (bit-xor 5 3) (bit-xor 5 6)) ; 6 3
```
