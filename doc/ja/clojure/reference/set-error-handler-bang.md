# set-error-handler!

`(set-error-handler! a h)`

agent のエラーハンドラを設定し、`nil` を返します。ハンドラはアクションが失敗したとき agent とエラーを引数に呼ばれ、ハンドラ自身の失敗は無視されます。モードは変えません（`set-error-mode!`）。関数値として動きます。

```clojure
(def a (agent 0 :error-mode :continue))
(set-error-handler! a (fn [ag e] (println "failed:" (ex-message e))))
(send a (fn [_] (throw (Exception. "boom"))))
(send a inc)
(await a)
(println @a)
```

```
failed: boom
1
```
