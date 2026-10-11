# set-error-handler!

`(set-error-handler! a h)`

Sets the agent's error handler, called with the agent and the error when an action
fails (a failure of the handler itself is ignored), and answers `nil`. It leaves the
mode as it is (`set-error-mode!`). Works as a function value.

```clojure
(def a (agent 0 :error-mode :continue))
(set-error-handler! a (fn [ag e] (println "failed:" (ex-message e))))
(send a (fn [_] (throw (Exception. "boom"))))
(send a inc)
(println @a)
```

```
failed: boom
1
```
