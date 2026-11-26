# when-not

`(when-not test body...)`

テストが truthy でないとき本体を実行し、最後の値で答えます。本体なし・テスト truthy では
`nil` です。

```clojure
(println (when-not false :ran)) ; :ran
(println (when-not true :ran)) ; nil
```
