# error-mode

`(error-mode a)`

The agent's error mode: `:fail` by default, `:continue` by default when it was made
with an `:error-handler`. Works as a function value.

```clojure
(println (error-mode (agent 0)) (error-mode (agent 0 :error-handler (fn [a e] nil))))
```

```
:fail :continue
```
