# locking

`(locking x body...)`

`x` のロックを保持したまま本体を実行し、本体の最後の値を返します。スレッドがあるインタプリタと JVM（Ring のハンドラはリクエストごとに一つのスレッドで動きます）では、ロックは値ごとに保持する再入可能なミューテックスです。値は同一性で区別し、数値は値で、キーワードは綴りで区別します。二つの wasm バックエンドはシングルスレッドなので、本体をそのまま実行します。ロックが `nil` のときは本体の前に `NullPointerException` を投げます。本体の中の `recur` は、`try` の中と同じく拒否されます。

仕様との差異: ロック表は渡された値をプログラムの実行中ずっと保持します。また `NullPointerException` のメッセージは、Clojure が生成した名前の代わりにローカル名 `locklocal` を示します。

```clojure
(def hits (atom 0))
(println (locking hits (swap! hits inc))) ; 1
(println (locking :log :done))            ; :done
```
