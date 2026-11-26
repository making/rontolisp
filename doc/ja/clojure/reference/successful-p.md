# successful?

`(successful? summary)`

`run-tests` の集計に失敗もエラーもなければ `true`、それ以外は `false` を返します。

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
