# promise

`(promise)`

`clojure.core/promise`: 未配達の promise です。[deliver](deliver.md) が配達するまで `deref`（と `@`）はブロックします。`future?` はそれに `false` を答え、`realized?` は配達されたかを答え、タイムアウト付きの `deref` は未配達の間デフォルトを答えます。シングルスレッドの WASM バックエンドでは呼び出しが代わりに拒否されます。値としては 0 引数の関数です。

```clojure
(let [p (promise)] (deliver p 7) (println @p (realized? p))) ; 7 true
```
