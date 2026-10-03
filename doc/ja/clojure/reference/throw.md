# throw

`(throw ex)`

`ex` をシグナルします。例外 -- `ex-info`、throwable の構築（`(Exception. "m")`）、捕捉した例外や実行時エラー、インタプリタと JVM ではホストの `Throwable` -- はそれ自身としてシグナルされるため、`catch` はそのクラス名、メッセージ、データ、cause を見ます。それ以外は Clojure 記法でのレンダリングを通ってシグナルされます。throw された文字列はその文字列をメッセージに保ち、throw されたマップはマップとして表示されます（オラクルは `Throwable` でない値を拒否します）。

```clojure
(println (try (throw (ex-info "boom" {:code 42})) (catch Exception e (get (ex-data e) :code)))) ; 42
(println (try (throw (IllegalStateException. "bad")) (catch Exception e (.getMessage e)))) ; bad
(println (try (throw "plain") (catch Exception e (str "got-" e)))) ; got-plain
```
