# bit-and

`(bit-and x y & more)`

オペランドを 64 ビット 2 の補数整数として AND したもの。oracle と同じ。64 ビット整数以外は拒否される（`bit operation not supported for: class ...`。`nil` は `NullPointerException`）。範囲内の `N` サフィックス付きリテラルはここでは通常の整数として読まれるため、`(bit-and 5N 2)` は oracle が `BigInt` として拒否するのに対して答えを返す。値としては 2 引数以上を取る。

```clojure
(println (bit-and 5 3) (bit-and 1 2 3 4)) ; 1 0
```
