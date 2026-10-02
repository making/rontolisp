# Tests (clojure.test)

`clojure.test` resolves like `clojure.string`: `(:require [clojure.test :refer :all])`,
`(:use clojure.test)` or an alias. A test is a zero-argument function registered per
namespace; reports follow the oracle's layout (`FAIL in (test) (file:line)`, the contexts,
the message, `expected:`/`  actual:`) and go to the stream `*out*` was when the program
started, so `with-out-str` never captures one. `use-fixtures` is refused by name.

| Name | Example | Result |
|---|---|---|
| `deftest` | `(deftest t (is (= 1 1)))` | defines `t` |
| `is` | `(is (= 2 (+ 1 1)))` | `true` |
| `are` | `(are [x] (pos? x) 1 2)` | `true` |
| `testing` | `(testing "ctx" (is true))` | `true` |
| `run-tests` | `(:test (run-tests))` | the test count |
| `run-all-tests` | `(run-all-tests #"demo.*")` | the summary map |
| `successful?` | `(successful? (run-tests))` | `true` |
