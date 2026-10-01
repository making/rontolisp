# clojure.string/join

`(clojure.string/join coll)`
`(clojure.string/join sep coll)`

`coll` の seq ビューを 1 つの文字列へ連結します。各部分は `str` され、間に `sep`（デフォルト `""`）が入ります。`alias/var`（`(s/join ...)`）や referred な裸の `join` としても到達し、関数値としても動きます。

```clojure
(ns demo (:require [clojure.string :as s]))
(println (s/join "," ["a" "b" "c"])) ; a,b,c
(println (clojure.string/join [1 2 3])) ; 123
```
