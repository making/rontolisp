# remove-watch

`(remove-watch reference key)`

Takes the watch `add-watch` added under `key` off the reference, answering the reference;
a key with no watch is no error. Works as a function value.

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
