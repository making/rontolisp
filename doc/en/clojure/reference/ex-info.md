# ex-info

`(ex-info msg map)` `(ex-info msg map cause)`

Builds an exception carrying a message, a data map and an optional cause. `throw` signals it,
`ex-message`/`ex-data`/`ex-cause` read it back, and `str` answers the oracle's `toString`
(`clojure.lang.ExceptionInfo: msg {data}`). Works as a function value too.

```clojure
(println (ex-message (ex-info "boom" {:code 42}))) ; boom
(println (ex-data (apply ex-info ["v" 2]))) ; 2
(println (str (ex-info "x" {:a 1}))) ; clojure.lang.ExceptionInfo: x {:a 1}
```
