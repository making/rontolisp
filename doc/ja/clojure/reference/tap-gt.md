# tap>

`(tap> x)`

`clojure.core/tap>`: [add-tap](add-tap.md) が追加したすべての tap に `x`（`nil` も）を渡し、`true` を答えます。インタプリタと JVM では値はオラクルと同じ 1024 個のキューに入り、tap スレッドがそれを取り出すので、満杯なら `false` を答えます。シングルスレッドの WASM バックエンドでは `tap>` が戻る前に tap を呼びます。tap の失敗は無視します。値としては 1 引数の関数です。

```clojure
(def seen (atom []))
(add-tap #(swap! seen conj %))
(println (tap> :t)) ; true
(loop [] (when (empty? (locking seen @seen)) (recur)))
(println @seen) ; [:t]
```
