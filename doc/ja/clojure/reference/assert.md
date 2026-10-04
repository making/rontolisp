# assert

`(assert test)` / `(assert test msg)`

テストが真なら `nil`、そうでなければシグナルします。メッセージは `Assert failed: <form>`、メッセージ付きなら
`Assert failed: <msg>\n<form>` で、失敗したフォームは oracle と同様に readable に出力されます。
メッセージは else 節にあるため、失敗時にのみ評価されます -- oracle と同様に遅延します。
`and`/`or` と同様に、`assert` に関数値はありません。

```clojure
(println (assert (= 1 1))) ; nil
(println (try (assert (= 1 2) "oops") (catch AssertionError e (ex-message e)))) ; Assert failed: oops、次の行に (= 1 2)
```
