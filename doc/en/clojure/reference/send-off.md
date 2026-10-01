# send-off

`(send-off a f args...)`

Like `send`: the oracle runs blocking sends on a separate pool, but nothing
blocks here, so the two verbs are indistinguishable. Answers the agent.

```clojure
(def a (agent 1))
(println @(send-off a * 6)) ; 6
```
