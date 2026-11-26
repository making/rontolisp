# (clojure.set)

The relational set library, reached as `alias/var`, `clojure.set/var`, or a referred bare var; each works as a function value too. The algorithms are the oracle's: `union` grows its largest input, `intersection` shrinks its smallest, `difference` and `select` shrink the first, so an input nothing changes is answered itself and `nil` stays `nil`. A relation is a set of maps, records included.

| Name | Example | Result |
|---|---|---|
| `clojure.set/union` | `(clojure.set/union #{1} #{1})` | `#{1}` |
| `clojure.set/intersection` | `(clojure.set/intersection #{1 2} #{2 3})` | `#{2}` |
| `clojure.set/difference` | `(clojure.set/difference #{1 2} #{2})` | `#{1}` |
| `clojure.set/select` | `(clojure.set/select odd? #{1 2})` | `#{1}` |
| `clojure.set/project` | `(clojure.set/project #{{:a 1 :b 2}} [:a])` | `#{{:a 1}}` |
| `clojure.set/rename-keys` | `(clojure.set/rename-keys {:a 1} {:a :b})` | `{:b 1}` |
| `clojure.set/rename` | `(clojure.set/rename #{{:a 1}} {:a :b})` | `#{{:b 1}}` |
| `clojure.set/index` | `(clojure.set/index #{{:a 1}} [:a])` | `{{:a 1} #{{:a 1}}}` |
| `clojure.set/map-invert` | `(clojure.set/map-invert {:a 1})` | `{1 :a}` |
| `clojure.set/join` | `(clojure.set/join #{{:a 1}} #{{:a 1 :b 2}})` | `#{{:a 1, :b 2}}` |
| `clojure.set/subset?` | `(clojure.set/subset? #{1} #{1 2})` | `true` |
| `clojure.set/superset?` | `(clojure.set/superset? #{1 2} #{1})` | `true` |
