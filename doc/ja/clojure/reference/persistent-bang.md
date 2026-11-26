# persistent!

`(persistent! tr)`

トランジェント `tr` が編集したコレクションを、コピーせずに返します。以後 `tr` はどの操作も
オラクルの `IllegalAccessError`（`Transient used after persistent! call`）として拒否します。
`ITransientCollection` を実装する型はその `persistent` を返します。値としては1引数関数です。

```clojure
(println (persistent! (assoc! (transient {}) :a 1))) ; {:a 1}
(let [t (transient [])] (persistent! t) (println (try (count t) (catch Throwable e (ex-message e))))) ; Transient used after persistent! call
```
