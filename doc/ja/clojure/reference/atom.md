# atom

`(atom v & options)`

`v` を、状態を扱うすべての動詞が読み書きするセルに包みます。`(deref a)` と `@a` が読み取りで、リーダー形式は同じ操作です。動詞の集合は `deref`/`swap!`/`reset!`/`compare-and-set!` と `swap-vals!`/`reset-vals!` で、いずれも関数値として動くため `(map deref atoms)` が動きます。アトムでないものへの誤用はシグナルを上げます。

オプションは Clojure の `(apply hash-map options)` と同じに読みます。`:meta` はアトムのメタデータを、`:validator` は新しい値がすべて通るべき関数（`set-validator!`）を設定し、初期値もこれを通る必要があります。ほかのキーは無視し、値のないキーは拒否します。watch は `add-watch` で加えます。

```clojure
(def a (atom 1 :validator pos? :meta {:doc "hits"}))
(println @a (meta a))
(println (swap! a + 10 20))
(println (try (reset! a -1) (catch IllegalStateException e (ex-message e))) @a)
(println (map deref [a (atom 2)]))
```

```
1 {:doc hits}
31
Invalid reference state 31
(31 2)
```
