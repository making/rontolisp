# commute

`(commute r f args...)`

`alter` と同じですが、`f` はコミット時に最新の値へ適用され（競合がなければ
オラクル同様2回走ります）、トランザクションをリトライさせることはありません。
同じトランザクション内で後から同じ ref へ `alter` や `ref-set` を行うと、
オラクルの "Can't set after commute" 拒否になります。`alter` 同様 `dosync`
が必要です。

```clojure
(def r (ref 1))
(dosync (commute r * 6))
(println @r) ; 6
```
