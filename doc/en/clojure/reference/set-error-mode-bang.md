# set-error-mode!

`(set-error-mode! a m)`

Sets the agent's error mode and answers `nil`: in `:continue` a failing action is
skipped and the queue goes on; any other mode (`:fail`) keeps the error and stops the
agent until `restart-agent`. Works as a function value.

```clojure
(def a (agent 0))
(set-error-mode! a :continue)
(send a (fn [_] (throw (Exception. "boom"))))
(send a inc)
(await a)
(println @a (agent-error a))
```

```
1 nil
```
