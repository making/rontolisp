# try

`(try expr* (catch Exception e expr*)* (finally expr*)?)`

本体の式を評価し、最後の値を返します。`try` は `unwind-protect` の中の `handler-case` を守ります。`finally` の本体は出口で走り、結果はそれを乗り越えて残ります。各 catch 節は catch-all のエラー節へ低レベル化されます -- クラスは区別されず、節は順に試され、最初のものが勝ちます。その変数は Common Lisp のコンディションを束縛し、`ex-data`/`ex-message` がそれを読みます。

```clojure
(println (try 1 (catch Exception e 2) (finally nil))) ; 1
(println (try (throw "boom") (catch Exception e (str "got-" e)))) ; got-boom
(println (try (+ 1 2) (catch Exception e "no"))) ; 3
```
