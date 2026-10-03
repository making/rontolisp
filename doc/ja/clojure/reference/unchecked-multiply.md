# unchecked-multiply

`(unchecked-multiply a b)`

整数の積を64ビットに折り返して返すので、`(unchecked-multiply 4611686018427387904 4)` は `0` です。doubleと比は通常の結果です。`nil` と非数はシグナルします。値としては2引数の関数です。

64ビットを超える整数もここでは通常の整数なので、オラクルでは折り返されない bigint（`100000000000000000000N`）も折り返します。

```clojure
(println (unchecked-multiply 4611686018427387904 4)) ; 0
```
