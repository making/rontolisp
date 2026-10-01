# clojure.string/join

`(clojure.string/join coll)`
`(clojure.string/join sep coll)`

Joins the seq view of `coll` into one string, the parts `str`'d and `sep` (default `""`)
between them. Reached also as `alias/var` (`(s/join ...)`) or a referred bare `join`; works
as a function value too.

```clojure
(ns demo (:require [clojure.string :as s]))
(println (s/join "," ["a" "b" "c"])) ; a,b,c
(println (clojure.string/join [1 2 3])) ; 123
```
