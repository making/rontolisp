# agent

`(agent v & options)`, options `:validator f`, `:error-handler h`, `:error-mode m`, `:meta m`

An agent holds a value that the actions sent with `send`/`send-off` update one at a
time, in the order sent; `deref`/`@` reads it. On the interpreter and the JVM the actions
run on a shared pool of worker threads: a send only queues its action and returns -- even
an idle agent's action runs after `send` returns, so a slow action never blocks the sender
-- while `await` waits for the actions sent so far. On the single-threaded WASM legs a send
to an idle agent still runs its action on the sender's thread before it returns. A `:validator` must accept
the initial value and every action's answer. A failing action fails the agent in the
`:fail` mode, the default without an `:error-handler` (`agent-error`,
`restart-agent`); with one the default is `:continue`. Options are read like the
oracle's `(apply hash-map options)`: an unknown key is ignored, a key without a value
is refused. `*agent*` is bound to the acting agent while an action runs, `nil` outside
one. [`future`](future.md) and [`promise`](promise.md) do run where threads exist instead: a future runs its body on its own thread.

```clojure
(def a (agent 0))
(send a + 40 2)
(println @a) ; 42
```
