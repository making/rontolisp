# ref-set

`(ref-set r v)`

`dosync` の内側で ref の値を置き換え、その値を返します。ref の validator はトランザクションの終わりに最終値に対して走ります。`reset!` 同様、関数値として動きます。

```clojure
(def r (ref 0))
(dosync (ref-set r 41))
(println @r) ; 41
```
