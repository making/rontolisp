# await-for

`(await-for timeout-ms a...)`

`await` with a limit: runs the actions sent to the agents so far, or waits at most
`timeout-ms` milliseconds for the thread running them, answering `true` when they all ran
and `false` when the time ran out (an action queued behind a failure never runs). Like
`await`, it signals inside an action (`Can't await in agent action`), inside a transaction
(`await-for in transaction`) and on a failed agent.

```clojure
(def a (agent 0))
(send a + 5)
(println (await-for 1000 a) @a)
```

```
true 5
```
