# are

`(are [names...] template args...)`

引数のグループごとに 1 つの `is` へ展開します。各グループは、表明を lower する前に
`template` の中の `names` を深さを問わず置き換えます。どの報告も `are` 式の行を示します。
引数の数が名前の数で割り切れないときは、本家と同じメッセージ
`The number of args doesn't match are's argv.` で拒否します。

```clojure
(ns demo (:require [clojure.test :refer :all]))
(deftest squares
  (are [x y] (= y (* x x))
    2 4
    3 9
    4 15))
(squares)
```

```

FAIL in (squares) (NO_SOURCE_FILE:3)
expected: (= 15 (* 4 4))
  actual: (not (= 15 16))
```
