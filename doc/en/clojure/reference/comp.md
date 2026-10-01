# comp

`(comp fns...)`

Answers the composition: the rightmost spreads the arguments, each outer wraps
one result. No functions is `identity`, one is itself. As a value the function
list composed at run time, so `(apply comp fns)` runs.

```clojure
(println ((comp inc inc) 5)) ; 7
(println (map (comp inc inc) [1 2])) ; (3 4)
```
