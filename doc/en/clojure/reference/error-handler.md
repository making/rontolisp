# error-handler

`(error-handler a)`

The agent's error handler, `nil` for none. Works as a function value.

```clojure
(println (error-handler (agent 0)))
```

```
nil
```
