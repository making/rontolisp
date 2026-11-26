# await

`(await a...)`

Runs the actions sent to the agents so far, or waits for the thread running them,
answering `nil`. Inside an action it signals (`Can't await in agent action`), and on a
failed agent like `send`, like the oracle.

```clojure
(def a (agent 0))
(send a inc)
(println (await a)) ; nil
(println @a) ; 1
```
