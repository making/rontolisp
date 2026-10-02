# successful?

`(successful? summary)`

Answers `true` when the `run-tests` summary counts no failure and no error, `false`
otherwise.

```clojure
(ns demo (:require [clojure.test :refer :all]))
(deftest ok (is true))
(println (successful? (run-tests)))
```

```

Testing demo

Ran 1 tests containing 1 assertions.
0 failures, 0 errors.
true
```
