# ex-message

`(ex-message ex)`

Answers an exception's message: an `ex-info`'s or a throwable construction's (`nil` when it
has none), a caught runtime error's report, `nil` for a value that is no exception, like the
oracle. `.getMessage` and `.getLocalizedMessage` answer the same. Works as a function value
too.

```clojure
(println (ex-message (ex-info "boom" {}))) ; boom
(println (map ex-message [(ex-info "m" 1) "nope"])) ; (m nil)
(println (try (assoc [0 1] :a :x) (catch Exception e (.getMessage e)))) ; Key must be integer
```
