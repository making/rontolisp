# remove-watch

`(remove-watch reference key)`

`add-watch` が `key` で加えた watch を reference から外し、reference を返します。watch のないキーでもエラーにはなりません。関数値として動きます。

```clojure
(def a (atom 0))
(add-watch a :log (fn [k r old new] (println old "->" new)))
(swap! a inc)
(remove-watch a :log)
(swap! a inc)
(println @a)
```

```
0 -> 1
2
```
