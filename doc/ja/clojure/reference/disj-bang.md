# disj!

`(disj! tr)` `(disj! tr x & xs)`

トランジェントのセット `tr` から各メンバーをその場で取り除き、`tr` を返します。1引数なら
その引数です。それ以外のトランジェントは拒否されます。値としても同じアリティを取ります。

```clojure
(println (persistent! (disj! (transient #{1 2 3}) 1 2))) ; #{3}
```
