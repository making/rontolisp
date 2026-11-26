# some-fn

`(some-fn p q...)`

Answers a function returning the first truthy `(p x)` over the given predicates and
its arguments, in the oracle's order: one to three predicates try the first three
arguments predicate by predicate, and failing answer the last `(p x)` tried (`false` or
`nil`); the arguments past three, and four or more predicates, fail with `nil`. As a
value it takes one or more predicates.

```clojure
(println ((some-fn :a :b) {:b 2})) ; 2
(println ((some-fn odd? pos?) -2 -4)) ; false
(println ((some-fn :a :b) {:b 1} {:a 2})) ; 2
```
