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
;; the pool runs the action after send returns: wait until the failure lands
(loop [] (when-not (agent-error a) (try (await-for 10 a) (catch RuntimeException e nil)) (recur)))
(println (try (send a inc) (catch RuntimeException e (ex-message e))))
(println (restart-agent a 10))
(send a inc)
(await a)
(println @a (agent-error a))
```

```
Agent is failed, needs restart
10
11 nil
```
