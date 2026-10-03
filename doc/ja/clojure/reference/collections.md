# マップ・セット・ベクター

マップとセットは equal ハッシュテーブルで、その場では変更されません:全操作が新しいテーブルを作るので、永続性は観測可能な形で保たれます。map/set の反復順はテーブルの走査順（未規定）です。キーは `=` で比較されるので、ベクター・リスト・マップ・セットのキーも等しいキーを見つけます。配列は general です。`(make-array Class dim...)` はクラスを無視し、`aget` で読み、`aset` で書き、`alength` で測ります。

| Name | Example | Result |
|---|---|---|
| `vector` | `(vector 1 2)` | `[1 2]` |
| `vector?` | `(vector? [1])` | `true` |
| `vec` | `(vec '(1 2))` | `[1 2]` |
| `hash-map` | `(hash-map :a 1)` | `{:a 1}` |
| `array-map` | `(array-map :a 1)` | `{:a 1}` |
| `assoc` | `(assoc {:a 1} :b 2)` | `{:a 1, :b 2}` |
| `dissoc` | `(dissoc {:a 1} :a)` | `{}` |
| `get` | `(get {:a 1} :b :none)` | `:none` |
| `contains?` | `(contains? {:a 1} :a)` | `true` |
| `find` | `(find {:a 1} :a)` | `[:a 1]` |
| `keys` | `(keys (hash-map :a 1))` | `(:a)` |
| `vals` | `(vals (hash-map :a 1))` | `(1)` |
| `merge` | `(merge {:a 1} {:b 2})` | `{:a 1, :b 2}` |
| `conj` | `(conj [1 2] 3)` | `[1 2 3]` |
| `disj` | `(disj #{1 2} 1)` | `#{2}` |
| `set` | `(set [1 2])` | `#{1 2}` |
| `update` | `(update {:a 1} :a inc)` | `{:a 2}` |
| `update-in` | `(update-in {:a {:b 1}} [:a :b] inc)` | `{:a {:b 2}}` |
| `assoc-in` | `(assoc-in {} [:a :b] 1)` | `{:a {:b 1}}` |
| `get-in` | `(get-in {:a {:b 1}} [:a :b])` | `1` |
| `replace` | `(replace {0 :z} [0 1 0])` | `[:z 1 :z]` |
| `select-keys` | `(select-keys {:a 1 :b 2} [:a])` | `{:a 1}` |
| `merge-with` | `(merge-with + {:a 1} {:a 2})` | `{:a 3}` |
| `into` | `(into [] [1 2])` | `[1 2]` |
| `frequencies` | `(frequencies [:a :a])` | `{:a 2}` |
| `peek` | `(peek [1 2 3])` | `3` |
| `pop` | `(pop [1 2 3])` | `[1 2]` |
| `subvec` | `(subvec [1 2 3 4] 1 3)` | `[2 3]` |
| `update-keys` | `(update-keys {:a 1} name)` | `{"a" 1}` |
| `update-vals` | `(update-vals {:a 1} inc)` | `{:a 2}` |
| `reduce-kv` | `(reduce-kv (fn [acc k v] (+ acc v)) 0 {:a 1 :b 2})` | `3` |
| `defstruct` | `(do (defstruct s :a) (:a (struct s 1)))` | `1` |
| `struct` | `(do (defstruct s :a) (:a (struct s 1)))` | `1` |
| `struct-map` | `(do (defstruct s :a) (:a (struct-map s :a 1)))` | `1` |
| `make-array` | `(alength (make-array String 2))` | `2` |
| `aget` | `(let [a (make-array String 1)] (aset a 0 "x") (aget a 0))` | `"x"` |
| `aset` | `(let [a (make-array String 1)] (aset a 0 "x"))` | `"x"` |
| `alength` | `(alength (make-array String 2))` | `2` |
