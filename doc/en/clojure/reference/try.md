# try

`(try expr* (catch Exception e expr*)* (finally expr*)?)`

Evaluates the body expressions and answers the last one. `try` guards a `handler-case` inside an
`unwind-protect`: the `finally` body runs on the way out, and the answer survives it. Each catch
clause lowers to the catch-all error clause -- the classes are not distinguished, the clauses are
tried in order and the first one wins; its variable binds the Common Lisp condition, which
`ex-data`/`ex-message` read.

```clojure
(println (try 1 (catch Exception e 2) (finally nil))) ; 1
(println (try (throw "boom") (catch Exception e (str "got-" e)))) ; got-boom
(println (try (+ 1 2) (catch Exception e "no"))) ; 3
```
