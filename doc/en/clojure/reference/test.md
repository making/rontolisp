# Tests (clojure.test)

`clojure.test` resolves like `clojure.string`: `(:require [clojure.test :refer :all])`,
`(:use clojure.test)` or an alias. A test is a zero-argument function registered per
namespace; reports follow the oracle's layout (`FAIL in (test) (file:line)`, the contexts,
the message, `expected:`/`  actual:`) and go to the stream `*out*` was when the program
started, so `with-out-str` never captures one. `use-fixtures` is refused by name.

A result map counts and prints through `do-report` (`:pass` counts silently, `:fail`
and `:error` print the block, anything else answers nil); `report` is the `:type`
multimethod over the same arms. `*testing-vars*` holds one `:name` map per running test
(`nil` outside a run). A `defn` whose name carries a `:test` function in its metadata
registers it like a `deftest` (test.check's `defspec` relies on this), and a `defmethod`
on `assert-expr` extends `is` at lower time, like the oracle's macro consulting the
multimethod while compiling.

| Name | Example | Result |
|---|---|---|
| `deftest` | `(deftest t (is (= 1 1)))` | defines `t` |
| `is` | `(is (= 2 (+ 1 1)))` | `true` |
| `are` | `(are [x] (pos? x) 1 2)` | `true` |
| `testing` | `(testing "ctx" (is true))` | `true` |
| `run-tests` | `(:test (run-tests))` | the test count |
| `run-all-tests` | `(run-all-tests #"demo.*")` | the summary map |
| `successful?` | `(successful? (run-tests))` | `true` |
| `report` | `(report {:type :pass})` | `nil` |
| `do-report` | `(do-report {:type :fail :expected 1 :actual 2})` | `nil` |
| `*testing-vars*` | `(count *testing-vars*)` | the running test count |
| `testing-vars-str` | `(testing-vars-str {:file "t.clj" :line 1})` | `" (t.clj:1)"` |
| `with-test-out` | `(with-test-out (println :hi))` | `nil` |
