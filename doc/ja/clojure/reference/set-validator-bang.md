# set-validator!

`(set-validator! reference f)`

`f` を atom、ref、agent、var の validator にし、`nil` を返します。reference が取ろうとする値はすべて `f`（任意の関数値）を通る必要があり、通らなければ格納は `IllegalStateException` の `Invalid reference state` で拒否され、値はそのまま残ります。現在の値がまず `f` を通る必要があり、`nil` は validator を外します。atom は格納のたびに、ref はトランザクションのコミット時に、agent は各アクションの結果に、var は `alter-var-root` と、ルートを置き換える `def`・`defn`・`defmacro`・`deftest`・`with-redefs` のたびに検査します。`get-validator` がそれを返します。関数値として動きます。

```clojure
(def a (atom 2))
(set-validator! a even?)
(println (try (swap! a inc) (catch IllegalStateException e (ex-message e))) @a)
(println (fn? (get-validator a)))
(set-validator! a nil)
(println (swap! a inc) (get-validator a))
```

```
Invalid reference state 2
true
3 nil
```
