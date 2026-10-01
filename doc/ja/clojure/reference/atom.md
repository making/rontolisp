# atom

`(atom v)`

`v` を、状態を扱うすべての動詞が読み書きするセルに包みます。`(deref a)` と `@a` が読み取りで、リーダー形式は同じ操作です。動詞の集合は `deref`/`swap!`/`reset!`/`compare-and-set!` で、いずれも新しい値を返り、関数値として動くため `(map deref atoms)` が動きます。アトムでないものへの誤用はシグナルを上げます。`add-watch`/`remove-watch` はなく、watch は名前で拒否されます。

```clojure
(def a (atom 1))
(println @a) ; 1
(println (swap! a + 10 20)) ; 31
(println (map deref [a (atom 2)])) ; (31 2)
```
