# dissoc!

`(dissoc! tr k & ks)`

トランジェントのマップ `tr` から各キーをその場で取り除き、`tr` を返します。持たないキーは
無視します。それ以外のトランジェントは拒否されます。値としても同じ引数を取ります。

```clojure
(println (persistent! (dissoc! (transient {:a 1 :b 2}) :a :z))) ; {:b 2}
```
