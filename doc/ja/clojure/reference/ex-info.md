# ex-info

`(ex-info msg map)` `(ex-info msg map cause)`

メッセージ、データマップ、省略可能な cause を持つ例外を組み立てます。`throw` がそれをシグナルし、`ex-message`/`ex-data`/`ex-cause` が読み返し、`str` はオラクルの `toString`（`clojure.lang.ExceptionInfo: msg {data}`）を返します。関数値としても動きます。

```clojure
(println (ex-message (ex-info "boom" {:code 42}))) ; boom
(println (ex-data (apply ex-info ["v" 2]))) ; 2
(println (str (ex-info "x" {:a 1}))) ; clojure.lang.ExceptionInfo: x {:a 1}
```
