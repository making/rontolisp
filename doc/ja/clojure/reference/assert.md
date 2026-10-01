# assert

`(assert test)` / `(assert test msg)`

テストが真なら `nil`、そうでなければシグナルします（`Assert failed:` 接頭辞のエラーです）。
メッセージは else 節にあるため、失敗時にのみ評価されます -- oracle と同様に遅延します。
`and`/`or` と同様に、`assert` に関数値はありません。

```clojure
(println (assert (= 1 1))) ; nil
(println (try (assert (= 1 2) "oops") (catch Exception e (ex-message e)))) ; Assert failed: oops
```
