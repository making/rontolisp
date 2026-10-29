# add-tap

`(add-tap f)`

`clojure.core/add-tap`: [tap>](tap-gt.md) が呼ぶ tap に `f` を追加し、`nil` を答えます。同じ tap を 2 回追加しても 1 つです。tap は追加した順に呼ばれます。インタプリタと JVM では tap スレッドで、WASM バックエンドでは `tap>` の呼び出しの中で呼ばれます。値としては 1 引数の関数です。

```clojure
(def seen (atom []))
(def record #(swap! seen conj %))
(println (add-tap record) (add-tap record)) ; nil nil
(tap> 1)
(loop [] (when (empty? (locking seen @seen)) (recur)))
(println @seen) ; [1]
```
