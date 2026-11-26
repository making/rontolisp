# テスト (clojure.test)

`clojure.test` は `clojure.string` と同じく解決します。`(:require [clojure.test :refer :all])`、
`(:use clojure.test)`、別名のどれでも使えます。テストは名前空間ごとに登録される引数 0 の
関数です。報告の形は本家に従い（`FAIL in (test) (file:line)`、文脈、メッセージ、
`expected:`/`  actual:`）、プログラム開始時の `*out*` のストリームに書きます。そのため
`with-out-str` が報告を捕捉することはありません。`use-fixtures` は名前を挙げて拒否します。

結果マップは `do-report` で数えて報告します（`:pass` は黙って数え、`:fail` と `:error`
はブロックを表示し、それ以外は `nil` を返します）。`report` は同じ処理への `:type`
マルチメソッドです。`*testing-vars*` は実行中のテストごとに `:name` マップを 1 つ保持
します（実行の外では `nil`）。名前のメタデータに `:test` 関数を持つ `defn` は `deftest`
と同じく登録されます（test.check の `defspec` がこれに依存しています）。`assert-expr`
への `defmethod` は、コンパイル時にマルチメソッドを参照する本家のマクロと同様に、
読み込み時の展開で `is` を拡張します。

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
