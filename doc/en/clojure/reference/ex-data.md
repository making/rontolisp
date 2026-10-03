# ex-data

`(ex-data ex)`

Answers the data map of an `ex-info`, `nil` for any other value -- a throwable construction, a
runtime error, a thrown string or map carries no data. An `ex-info` built with `nil` data
answers `{}`, like the oracle. Works as a function value, so it travels through `map`.

```clojure
(println (ex-data (ex-info "m" {:code 7}))) ; {:code 7}
(println (map ex-data [(ex-info "m" 1) "nope"])) ; (1 nil)
```
