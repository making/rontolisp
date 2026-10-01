# await

`(await a...)`

Waits for the agents' sends, answering `nil`. Every send already ran, so this
only checks each argument is an agent cell and rendezvous.

```clojure
(def a (agent 0))
(send a inc)
(println (await a)) ; nil
(println @a) ; 1
```
