# deftest

`(deftest name body...)`

Defines the test `name`: a zero-argument function that runs `body` as one test, registered
under the current namespace in definition order. `run-tests` runs it; calling `(name)` runs
it alone. An error escaping the body is reported as `Uncaught exception, not in
assertion.` and the test ends. `deftest-` is the same definition. A `recur` in the body
targets the body itself.

```clojure
(ns demo (:require [clojure.test :refer :all]))
(deftest addition
  (is (= 4 (+ 2 2)))
  (is (= [1 2] '(1 2))))
(run-tests)
```

```

Testing demo

Ran 1 tests containing 2 assertions.
0 failures, 0 errors.
```
