# reset!

`(reset! atom v)`

`v` を格納し、それを返します。

```clojure
(def a (atom 1))
(println (reset! a 2)) ; 2
(println @a) ; 2
```
