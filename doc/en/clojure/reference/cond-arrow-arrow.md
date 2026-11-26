# cond->>

`(cond->> expr test step...)`

Threads `expr` through the steps, each list step taking the value last like `->>`,
but a step applies only when its test is truthy; every test evaluates either way, and
the answer is the last value the threading reached. Lowers to the threaded calls
around one temporary.

```clojure
(println (cond->> 5 true (conj [1]) false (conj [2]))) ; [1 5]
```
