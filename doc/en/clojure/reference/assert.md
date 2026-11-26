# assert

`(assert test)` / `(assert test msg)`

`nil` when the test is truthy, else an `AssertionError`, which an `Exception` catch does not
take. Its message is `Assert failed: <form>`,
or `Assert failed: <msg>\n<form>` with a message, the failed form printed readably like
the oracle. The message sits in the else branch, so it evaluates only on failure --
lazily, like the oracle. Like `and`/`or`, `assert` has no function value.

`assert` reads `*assert*` where it expands, like the oracle's macro: after a top-level
`(set! *assert* false)` (or `nil`) every later `assert` is `nil`, its test and message
unevaluated, until a top-level `set!` back to `true`. A `binding` of `*assert*` around
an `assert` changes nothing.

```clojure
(println (assert (= 1 1))) ; nil
(println (try (assert (= 1 2) "oops") (catch AssertionError e (ex-message e)))) ; Assert failed: oops, then (= 1 2) on the next line
```

```clojure
(set! *assert* false)
(println (assert (= 1 2)))
```

```
nil
```
