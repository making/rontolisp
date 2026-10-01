# ref-set

`(ref-set r v)`

`dosync` の内側で ref の値を置き換えます。ref の validator を通り、その値を返します。`reset!` 同様、関数値として動きます。

```clojure
(def r (ref 0))
(dosync (ref-set r 41))
(println @r) ; 41
```
