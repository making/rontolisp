# error-handler

`(error-handler a)`

agent のエラーハンドラを返し、なければ `nil` を返します。関数値として動きます。

```clojure
(println (error-handler (agent 0)))
```

```
nil
```
