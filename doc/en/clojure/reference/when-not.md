# when-not

`(when-not test body...)`

Runs the body unless the test is truthy, answering the last value -- `nil`
without a body or on a truthy test.

```clojure
(println (when-not false :ran)) ; :ran
(println (when-not true :ran)) ; nil
```
