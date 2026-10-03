# throw

`(throw ex)`

Signals `ex`. An exception -- an `ex-info`, a throwable construction (`(Exception. "m")`), a
caught exception or runtime error, a host `Throwable` on the interpreter and the JVM --
signals as itself, so `catch` sees its class name, message, data and cause. Anything else
signals through its Clojure-notation rendering: a thrown string keeps the string as its
message, a thrown map prints as the map (the oracle refuses a value that is no `Throwable`).

```clojure
(println (try (throw (ex-info "boom" {:code 42})) (catch Exception e (get (ex-data e) :code)))) ; 42
(println (try (throw (IllegalStateException. "bad")) (catch Exception e (.getMessage e)))) ; bad
(println (try (throw "plain") (catch Exception e (str "got-" e)))) ; got-plain
```
