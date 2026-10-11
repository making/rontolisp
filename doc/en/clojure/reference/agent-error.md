# agent-error

`(agent-error a)`

The error a failed agent stopped on (a throwing action, a refusing validator), `nil`
while it has not failed. The deprecated `(agent-errors a)` answers it in a list, `nil`
for none. Works as a function value.

```clojure
(def a (agent 1 :validator pos?))
(send a dec)
(println @a (ex-message (agent-error a)))
```

```
1 Invalid reference state
```
