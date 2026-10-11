# agent

`(agent v)` / `(agent v :validator validate-fn)`

An agent holds a value that the actions sent with `send`/`send-off` update one at a
time, in the order sent; `deref`/`@` reads it. There is no thread pool: a send to an
idle agent runs its action on the sender's thread before it returns, and one to an
agent another thread is running joins that thread's queue. A `:validator` must accept
the initial value and every action's answer. `*agent*` is bound to the acting agent
while an action runs, `nil` outside one. `future`/`promise` stay refused by name
instead.

```clojure
(def a (agent 0))
(send a + 40 2)
(println @a) ; 42
```
