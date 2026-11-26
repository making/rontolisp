# testing

`(testing context body...)`

Runs `body` with `context` (a string, or anything `str` spells) added to the contexts every
report inside it prints, outermost first, and answers the body's value.

```clojure
(ns demo (:require [clojure.test :refer :all]))
(deftest arithmetic
  (testing "addition"
    (testing "with zero"
      (is (= 1 (+ 1 1))))))
(arithmetic)
```

```

FAIL in (arithmetic) (NO_SOURCE_FILE:5)
addition with zero
expected: (= 1 (+ 1 1))
  actual: (not (= 1 2))
```
