# constantly

`(constantly x)`

Answers a function answering `x` whatever the arguments -- evaluated once. As a
value a one-argument lambda over the same closure.

```clojure
(println ((constantly 7) 1 2 3)) ; 7
```
