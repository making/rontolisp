# deftest

`(deftest name body...)`

テスト `name` を定義します。`name` は `body` を 1 つのテストとして実行する引数 0 の関数で、
現在の名前空間に定義順で登録されます。`run-tests` が実行し、`(name)` と呼べばそのテストだけを
実行します。本体から漏れたエラーは `Uncaught exception, not in assertion.` として報告され、
そのテストは終わります。`deftest-` も同じ定義です。本体の `recur` は本体自身を対象にします。

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
