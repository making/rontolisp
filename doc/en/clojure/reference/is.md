# is

`(is form)` `(is form msg)`

Asserts `form` and answers its value. A truthy value passes; anything else reports
`FAIL in (test) (file:line)`, the `testing` contexts, `msg`, the expected form and the
actual value. For a function call the arguments are evaluated first and the actual value is
`(not (f values...))`; for a macro, a special form, a local or a keyword it is the value
itself. An error inside `form` reports `ERROR` and answers `nil`. `(thrown? C body...)`
passes when the body signals and answers the condition; `(thrown-with-msg? C re body...)`
also requires `re` to find a match in the message. The class `C` is not checked: any
condition matches, like `catch`. Outside `run-tests` the report still prints, without
counting.

```clojure
(ns demo (:require [clojure.test :refer :all]))
(println (is (= 2 (+ 1 1))))
(println (is (= "a" "b") "strings differ"))
(println (is (thrown? Exception (throw (ex-info "boom" {})))))
```

```
true

FAIL in () (NO_SOURCE_FILE:3)
strings differ
expected: (= "a" "b")
  actual: (not (= "a" "b"))
false
clojure.lang.ExceptionInfo: boom {}
```
