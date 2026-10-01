# reify

`(reify Protocol (method [target & args] body...) ...)`

Answers one fresh dispatch value per evaluation with a row per method in each
protocol's table: a single-shot map plus methods (never `proxy`, which stays the
`java:` surface). Each instance dispatches through its own tag, so two instances
are never `=` to each other, like the oracle; `=` is identity otherwise. Method
groups stand under protocol names, like `extend-type`.

```clojure
(defprotocol P (m [x]))
(def a (reify P (m [_] :a)))
(def b (reify P (m [_] :b)))
(println (m a))       ; :a
(println (m b))       ; :b
(println (= a a))     ; true
(println (= a b))     ; false
(println (satisfies? P a)) ; true
```
