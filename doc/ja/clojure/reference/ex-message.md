# ex-message

`(ex-message ex)`

例外のメッセージを返します。`ex-info` や throwable の構築ではそのメッセージ（なければ `nil`）、捕捉した実行時エラーではその report、例外でない値ではオラクルと同じく `nil` です。`.getMessage` と `.getLocalizedMessage` も同じ値を返します。関数値としても動きます。

```clojure
(println (ex-message (ex-info "boom" {}))) ; boom
(println (map ex-message [(ex-info "m" 1) "nope"])) ; (m nil)
(println (try (assoc [0 1] :a :x) (catch Exception e (.getMessage e)))) ; Key must be integer
```
