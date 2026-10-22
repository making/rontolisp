# agent

`(agent v & options)`、オプションは `:validator f`・`:error-handler h`・`:error-mode m`・`:meta m`

agent は値を保持し、`send`/`send-off` で送ったアクションが送った順に1つずつその値を更新します。読むのは `deref`/`@` です。インタプリタと JVM ではアクションはワーカースレッドの共有プールで走ります。送信はアクションをキューに入れて戻るだけです。アイドルの agent への送信でも `send` が戻ってからアクションが走るため、重いアクションが送信側を止めません。`await` はそれまでに送られたアクションを待ちます。シングルスレッドの WASM レッグでは、アイドルの agent への送信はそのアクションを送信側のスレッドで走らせてから戻ります。`:validator` は初期値と各アクションの答えを受け入れる必要があります。失敗したアクションは、`:error-handler` がないときの既定である `:fail` モードでは agent を失敗状態にします（`agent-error`、`restart-agent`）。`:error-handler` があると既定は `:continue` です。オプションはオラクルの `(apply hash-map options)` 同様に読まれ、未知のキーは無視され、値のないキーは拒否されます。アクションの実行中 `*agent*` はその agent に束縛され、外側では `nil` です。スレッドが存在する場所では [`future`](future.md) と [`promise`](promise.md) が代わりに走ります。future は本体を独自のスレッドで実行します。

```clojure
(def a (agent 0))
(send a + 40 2)
(println @a) ; 42
```
