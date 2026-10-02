# is

`(is form)` `(is form msg)`

`form` を表明し、その値を返します。真の値なら成功です。それ以外は
`FAIL in (test) (file:line)`、`testing` の文脈、`msg`、期待した式、実際の値を報告します。
関数呼び出しでは引数を先に評価し、実際の値を `(not (f 値...))` と表示します。マクロ、
特殊形式、ローカル変数、キーワードが先頭のときは値そのものを表示します。`form` の中の
エラーは `ERROR` として報告され、`nil` を返します。`(thrown? C body...)` は本体が
シグナルすれば成功し、そのコンディションを返します。`(thrown-with-msg? C re body...)`
はさらに、メッセージの中に `re` が一致することを求めます。クラス `C` は検査しません。
`catch` と同じく、どのコンディションも一致します。`run-tests` の外でも報告は表示されますが、
集計はされません。

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
boom
```
