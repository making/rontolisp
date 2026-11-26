# pop!

`(pop! tr)`

トランジェントのベクター `tr` の最後のメンバーをその場で取り除き、`tr` を返します。空なら
オラクルの `IllegalStateException`（`Can't pop empty vector`）です。それ以外のトランジェントは
拒否されます。値としては1引数関数です。

```clojure
(println (persistent! (pop! (transient [1 2 3])))) ; [1 2]
```
