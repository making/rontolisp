# run-all-tests

`(run-all-tests)` `(run-all-tests re)`

Runs `run-tests` over every namespace the program named or that defined a test, or only
over the names `re` matches whole. The oracle also lists every loaded library namespace
(`clojure.core` and the like) when called without a pattern.

```clojure
(ns demo.a (:require [clojure.test :refer :all]))
(deftest a-test (is true))
(ns demo.b (:require [clojure.test :refer :all]))
(deftest b-test (is true))
(println (:test (run-all-tests #"demo\..*")))
```

```

Testing demo.a

Testing demo.b

Ran 2 tests containing 2 assertions.
0 failures, 0 errors.
2
```
