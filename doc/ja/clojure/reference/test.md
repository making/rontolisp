# テスト (clojure.test)

`clojure.test` は `clojure.string` と同じく解決します。`(:require [clojure.test :refer :all])`、
`(:use clojure.test)`、別名のどれでも使えます。テストは名前空間ごとに登録される引数 0 の
関数です。報告の形は本家に従い（`FAIL in (test) (file:line)`、文脈、メッセージ、
`expected:`/`  actual:`）、プログラム開始時の `*out*` のストリームに書きます。そのため
`with-out-str` が報告を捕捉することはありません。`use-fixtures` は名前を挙げて拒否します。

| Name | Example | Result |
|---|---|---|
| `deftest` | `(deftest t (is (= 1 1)))` | defines `t` |
| `is` | `(is (= 2 (+ 1 1)))` | `true` |
| `are` | `(are [x] (pos? x) 1 2)` | `true` |
| `testing` | `(testing "ctx" (is true))` | `true` |
| `run-tests` | `(:test (run-tests))` | the test count |
| `run-all-tests` | `(run-all-tests #"demo.*")` | the summary map |
| `successful?` | `(successful? (run-tests))` | `true` |
