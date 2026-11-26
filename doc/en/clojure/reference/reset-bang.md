# reset!

`(reset! atom v)`

Stores `v`, answering it.

```clojure
(def a (atom 1))
(println (reset! a 2)) ; 2
(println @a) ; 2
```
