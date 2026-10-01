# vswap!

`(vswap! volatile f x...)`

volatile の値と追加の引数へ `f` を適用し、その答えを格納して、新しい値を返します -- volatile と綴った `swap!` です。

```clojure
(def v (volatile! 1))
(println (vswap! v + 2)) ; 3
(println @v) ; 3
```
