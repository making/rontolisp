# throw

`(throw ex)`

`ex` をシグナルします。`ex-info` の値はそれ自身のコンディションとしてシグナルされるため、`catch` がそれを見て、`ex-data` はそのマップを返します。それ以外は Clojure 記法でのレンダリングを通ってシグナルされます -- throw された文字列はその文字列をメッセージに保ち、throw されたマップはマップとして表示されます。

```clojure
(println (try (throw (ex-info "boom" {:code 42})) (catch Exception e (get (ex-data e) :code)))) ; 42
(println (try (throw "plain") (catch Exception e (str "got-" e)))) ; got-plain
```
