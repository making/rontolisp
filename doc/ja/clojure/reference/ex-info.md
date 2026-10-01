# ex-info

`(ex-info msg map)`

メッセージとデータスロットを運ぶコンディションを組み立てます。report はメッセージを表示し、`throw` がそれをシグナルし、`ex-data`/`ex-message` がスロットを読み返します。関数値としても動きます。

```clojure
(println (ex-message (ex-info "boom" {:code 42}))) ; boom
(println (ex-data (apply ex-info ["v" 2]))) ; 2
```
