# send

`(send a f args...)`

Applies `f` to the agent's value and the arguments at once, through the
agent's validator, and answers the agent. Synchronous: async ordering is out,
so `(await a)` afterwards is a no-op rendezvous. Works as a function value.

```clojure
(def a (agent 1))
(println @(send a * 6)) ; 6
```
