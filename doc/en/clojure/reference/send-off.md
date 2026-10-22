# send-off

`(send-off a f args...)`

Like `send`: the action runs on the shared pool on the interpreter and the JVM -- the
oracle gives blocking actions an unbounded pool of their own, so a blocking action holds
a worker here. Answers the agent.

```clojure
(def a (agent 1))
(send-off a * 6)
(await a)
(println @a) ; 6
```
