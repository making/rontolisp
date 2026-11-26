# pop

`(pop coll)`

Answers a vector without its last member, a list or a [queue](persistent-queue.md) without its
first; `nil` of `nil`, an empty queue itself. An empty vector signals `Can't pop empty vector`, like the oracle; a string,
map, set or lazy seq signals. Popping a one-member list answers `nil`, where the
oracle prints `()`. As a value a one-argument function.

```clojure
(println (pop [1 2 3])) ; [1 2]
(println (pop '(1 2 3))) ; (2 3)
```
