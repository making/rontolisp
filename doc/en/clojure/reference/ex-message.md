# ex-message

`(ex-message cond)`

Answers the message of a condition: the message slot of an `ex-info`, anything else through its
Clojure-notation rendering -- a thrown string answers itself. Works as a function value too.

```clojure
(println (ex-message (ex-info "boom" {}))) ; boom
(println (map ex-message [(ex-info "m" 1) "nope"])) ; (m nope)
```
