# some-fn

`(some-fn p q...)`

Answers a function returning the first truthy `(p x)` over the given predicates and
its arguments. Failing, it answers what the oracle answers: the last `(p x)` tried
for one or two predicates over at most three arguments (`false` or `nil`), `nil`
otherwise. As a value it takes one or more predicates.

```clojure
(println ((some-fn :a :b) {:b 2})) ; 2
(println ((some-fn odd? pos?) -2 -4)) ; false
```
