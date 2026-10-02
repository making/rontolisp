# run-tests

`(run-tests)` `(run-tests ns...)`

指定した名前空間（クォートしたシンボルか文字列）のテストをすべて実行します。指定が
なければ現在の名前空間を実行します。名前空間ごとに `Testing ns` を表示し、最後に
`Ran N tests containing M assertions.` / `F failures, E errors.` の集計を表示します。
返り値は `{:test :pass :fail :error :type :summary}` のマップです。プログラムが一度も
名指しせず、テストも定義していない名前空間は、本家と同じ `No namespace: ... found` の
エラーになります。テストは定義順に実行します。関数値としても動くので、
`(apply run-tests nss)` も実行できます。

```clojure
(ns demo (:require [clojure.test :as t]))
(t/deftest ok (t/is true))
(t/deftest broken (t/is (= 1 2)))
(let [summary (t/run-tests 'demo)]
  (println (:test summary) (:pass summary) (:fail summary) (:error summary)))
```

```

Testing demo

FAIL in (broken) (NO_SOURCE_FILE:3)
expected: (= 1 2)
  actual: (not (= 1 2))

Ran 2 tests containing 2 assertions.
1 failures, 0 errors.
2 1 1 0
```
