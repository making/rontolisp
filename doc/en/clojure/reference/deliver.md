# deliver

`(deliver p v)`

`clojure.core/deliver`: delivers `v` to the promise `p`, answering the promise; a second delivery keeps the first value, like the oracle's. Anything but a promise is the oracle's cast failure (`nil`: `NullPointerException`). As a value a two-argument function.

```clojure
(let [p (promise)] (deliver p 1) (deliver p 2) (println @p)) ; 1
```
