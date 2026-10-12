# swap-vals!

`(swap-vals! atom f x...)`

`[old new]`、つまり `f` を適用した値と格納した値を返す `swap!` です。再試行ループ、validator、watch は同じです。関数値として動きます。

```clojure
(def a (atom 1))
(println (swap-vals! a + 10))
(println @a)
```

```
[1 11]
11
```
