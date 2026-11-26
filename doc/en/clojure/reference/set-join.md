# clojure.set/join

`(clojure.set/join xrel yrel)`
`(clojure.set/join xrel yrel km)`

Answers the relational join: each pair of members that agree on the keys the two relations' first members share -- or, with `km`, on the `xrel` keys `km` maps to `yrel` keys -- merged into one map. The smaller relation is indexed; with no key in common every pair joins, and an empty relation answers `#{}`. Reached also as `alias/var` or a referred bare `join`; works as a function value too.

```clojure
(ns demo (:require [clojure.set :as set]))
(def composers #{{:composer "Bach" :country "Germany"}})
(def nations #{{:nation "Germany" :language "German"}})
(println (= (set/join composers nations {:country :nation})
            #{{:composer "Bach" :country "Germany" :nation "Germany" :language "German"}})) ; true
(println (set/join #{{:a 1 :b 2}} #{{:a 2 :c 3}})) ; #{}
```
