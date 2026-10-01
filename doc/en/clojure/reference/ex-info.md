# ex-info

`(ex-info msg map)`

Builds a condition carrying a message and a data slot. Its report prints the message; `throw`
signals it and `ex-data`/`ex-message` read the slots back. Works as a function value too.

```clojure
(println (ex-message (ex-info "boom" {:code 42}))) ; boom
(println (ex-data (apply ex-info ["v" 2]))) ; 2
```
