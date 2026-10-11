# send

`(send a f args...)`

Queues the action applying `f` to the agent's value and the arguments, its answer
going through the agent's validator, and answers the agent. An idle agent runs it on
the sender's thread before `send` returns; an agent another thread is running runs it
there, after the actions queued before it. A send made inside a transaction or an
action runs once that completes. Works as a function value.

```clojure
(def a (agent 1))
(println @(send a * 6)) ; 6
```
