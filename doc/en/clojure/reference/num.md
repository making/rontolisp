# num

`(num x)`

Answers a number itself and `nil` as `nil`; anything else signals, like the oracle. As a value a
one-argument function.

```clojure
(println (num 1) (num 1/2)) ; 1 1/2
```
