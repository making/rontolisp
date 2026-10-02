# ensure-reduced

`(ensure-reduced x)`

`x` がすでに [`reduced`](reduced.md) ならそのまま、そうでなければ `(reduced x)` を返します。
`take` のトランスデューサーが最後の入力の後に返すものです。値としては1引数の関数です。

```clojure
(println (reduced? (ensure-reduced 1)) (unreduced (ensure-reduced (reduced 2)))) ; true 2
```
