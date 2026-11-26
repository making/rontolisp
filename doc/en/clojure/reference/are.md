# are

`(are [names...] template args...)`

Expands to one `is` per group of arguments: each group replaces `names` in `template`, at
any depth, before the assertion lowers. Every report names the `are` form's line. An
argument count that does not divide by the names is refused with the oracle's message
`The number of args doesn't match are's argv.`

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
