# restart-agent

`(restart-agent a v & options)`

Restarts a failed agent: `v`, which its validator must accept, becomes its value, its
error is cleared and the actions queued behind the failure run, or are dropped with
`:clear-actions true`. Answers `v`; an agent that has not failed is refused (`Agent
does not need a restart`). The deprecated `(clear-agent-errors a)` restarts it over its
own value. Works as a function value.

```clojure
(def a (agent 0))
(send a (fn [_] (throw (Exception. "boom"))))
(println (try (send a inc) (catch RuntimeException e (ex-message e))))
(println (restart-agent a 10))
(send a inc)
(println @a (agent-error a))
```

```
Agent is failed, needs restart
10
11 nil
```
