# agent

`(agent v)` / `(agent v :validator validate-fn)`

An agent is an atom cell holding a value to update with `send`/`send-off` and
read with `deref`/`@`. There is no thread pool on any backend, so a send
applies at once (see `send`); `await` is already past when it runs.
`*agent*` is bound to the acting agent while a send runs, `nil` outside one.
`future`/`promise`/`delay` stay refused by name instead.

```clojure
(def a (agent 0))
(send a + 40 2)
(println @a) ; 42
```
