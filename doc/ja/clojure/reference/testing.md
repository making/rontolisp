# testing

`(testing context body...)`

`context`（文字列、または `str` が綴る値）を文脈に加えて `body` を実行し、本体の値を
返します。内側の報告は、外側から順に文脈を表示します。

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
