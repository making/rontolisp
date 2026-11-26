# send

`(send a f args...)`

Queues the action applying `f` to the agent's value and the arguments, its answer
going through the agent's validator, and answers the agent. On the interpreter and the
JVM the action runs on the shared pool after `send` returns; an agent another thread is
running runs it there, after the actions queued before it. On the single-threaded WASM
legs an idle agent still runs it on the sender's thread before `send` returns. A send
made inside a transaction or an action runs once that completes. A failed agent refuses
it with a `RuntimeException`
(`Agent is failed, needs restart`) whose cause is the agent's error
(`restart-agent`). Works as a function value.

```clojure
(def a (agent 1))
(send a * 6)
(await a)
(println @a) ; 6
```
