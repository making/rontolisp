# agent

`(agent v)` / `(agent v :validator validate-fn)`

agent は、`send`/`send-off` で更新し `deref`/`@` で読む値を保持するアトムセルです。どのバックエンドにもスレッドプールがないため、send は即時に適用されます（`send` 参照）。`await` はそれが済んでから走るので、待ち合わせは no-op です。send の実行中 `*agent*` はその agent に束縛され、外側では `nil` です。`future`/`promise`/`delay` は名前で拒否されたままです。

```clojure
(def a (agent 0))
(send a + 40 2)
(println @a) ; 42
```
