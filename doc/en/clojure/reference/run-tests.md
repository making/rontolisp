# run-tests

`(run-tests)` `(run-tests ns...)`

Runs every test of the named namespaces (quoted symbols or strings), or of the current one
without any, printing `Testing ns` per namespace and the summary
`Ran N tests containing M assertions.` / `F failures, E errors.`. Answers the map
`{:test :pass :fail :error :type :summary}`. A namespace the program never named and
that defined no test is the oracle's `No namespace: ... found` error. Tests run in
definition order. Works as a function value too, so `(apply run-tests nss)` runs.

```clojure
(ns demo (:require [clojure.test :as t]))
(t/deftest ok (t/is true))
(t/deftest broken (t/is (= 1 2)))
(let [summary (t/run-tests 'demo)]
  (println (:test summary) (:pass summary) (:fail summary) (:error summary)))
```

```

Testing demo

FAIL in (broken) (NO_SOURCE_FILE:3)
expected: (= 1 2)
  actual: (not (= 1 2))

Ran 2 tests containing 2 assertions.
1 failures, 0 errors.
2 1 1 0
```
