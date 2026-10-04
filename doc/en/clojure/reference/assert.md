# assert

`(assert test)` / `(assert test msg)`

`nil` when the test is truthy, else a signal. Its message is `Assert failed: <form>`,
or `Assert failed: <msg>\n<form>` with a message, the failed form printed readably like
the oracle. The message sits in the else branch, so it evaluates only on failure --
lazily, like the oracle. Like `and`/`or`, `assert` has no function value.

```clojure
(println (assert (= 1 1))) ; nil
(println (try (assert (= 1 2) "oops") (catch AssertionError e (ex-message e)))) ; Assert failed: oops, then (= 1 2) on the next line
```
