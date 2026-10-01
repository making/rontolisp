# swap!

`(swap! atom f x...)`

アトムの値と追加の引数へ `f` を適用し、その結果を格納して、新しい値を返します。関数値としても動くため、`map`/`reduce` を渡れます。

```clojure
(def a (atom 1))
(println (swap! a + 10 20)) ; 31
(println @a)                ; 31
```

アトムでないものへの誤用はシグナルを上げます。`add-watch`/`remove-watch` はなく、watch は名前で拒否されます。
