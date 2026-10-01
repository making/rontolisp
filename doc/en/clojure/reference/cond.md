# cond

`(cond test expr... test expr...)`

Takes the test/expression pairs in order and, at the first truthy test, evaluates that
expression and answers it; with no pair taken the answer is `nil`. `:else` is true, so
it works as the catch-all. An ODD trailing arm -- a lone test with no expression --
is taken as the default, answering the test's value, where Clojure rejects the
malformed parity.

Deviation: the odd trailing arm is accepted as the default; Clojure requires an even form count.

```clojure
(println (cond (= 1 2) :one (= 1 1) :two :else :other)) ; two
(println (cond (= 1 2) :one :fallback))                 ; fallback
```
