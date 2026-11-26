# (clojure.set)

関係演算の集合ライブラリです。alias/var、clojure.set/var、refer された裸の名前のいずれでも届き、関数値としても動きます。アルゴリズムは本家と同じです。`union` は最も大きい入力を育て、`intersection` は最も小さい入力を縮め、`difference` と `select` は先頭の入力を縮めるので、何も変わらない入力はそれ自身が返り、`nil` は `nil` のままです。関係はマップ（レコードを含む）の集合です。

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
