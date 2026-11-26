# error-mode

`(error-mode a)`

agent のエラーモードを返します。既定は `:fail` で、`:error-handler` を付けて作った agent の既定は `:continue` です。関数値として動きます。

```clojure
(println (error-mode (agent 0)) (error-mode (agent 0 :error-handler (fn [a e] nil))))
```

```
:fail :continue
```
