# run-all-tests

`(run-all-tests)` `(run-all-tests re)`

プログラムが名指しした名前空間とテストを定義した名前空間すべてに `run-tests` を実行します。
`re` を渡すと、名前全体が `re` に一致するものだけを実行します。パターンなしで呼んだ場合、
本家は読み込み済みのライブラリの名前空間（`clojure.core` など）もすべて列挙します。

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
