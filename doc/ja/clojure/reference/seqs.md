# seq

seq は任意のコレクション上の strict なリストビューです:リストはそのまま通り抜け、ベクターと文字列は変換され、マップはエントリごとに 2 要素ベクターを、セットは要素ごとに 1 メンバーを供給します（走査順はテーブルのもので、未規定）。遅延はなく、全操作の空の結果は nil です。

| Name | Example | Result |
|---|---|---|
| `seq` | `(seq [1 2 3])` | `(1 2 3)` |
| `first` | `(first {:a 1})` | `[:a 1]` |
| `rest` | `(rest '(1 2 3))` | `(2 3)` |
| `next` | `(next [1])` | `nil` |
| `cons` | `(cons 1 [2 3])` | `(1 2 3)` |
| `list*` | `(list* 1 2 [3 4])` | `(1 2 3 4)` |
| `count` | `(count {:a 1 :b 2})` | `2` |
| `empty?` | `(empty? "")` | `true` |
| `map` | `(map inc [1 2 3])` | `(2 3 4)` |
| `filter` | `(filter odd? [1 2 3 4])` | `(1 3)` |
| `reduce` | `(reduce + 0 [1 2 3])` | `6` |
| `apply` | `(apply max 1 [2 3])` | `3` |
| `concat` | `(concat [1] '(2) #{3})` | `(1 2 3)` |
| `take` | `(take 2 [1 2 3])` | `(1 2)` |
| `drop` | `(drop 2 [1 2 3])` | `(3)` |
| `range` | `(range 0 6 2)` | `(0 2 4)` |
| `nth` | `(nth [1 2 3] 5 :none)` | `:none` |
| `keep` | `(keep inc [1 2 3])` | `(2 3 4)` |
| `keep-indexed` | `(keep-indexed (fn [i x] (when (odd? x) i)) [10 11 12])` | `(1)` |
| `map-indexed` | `(map-indexed vector [:a :b])` | `([0 :a] [1 :b])` |
| `every?` | `(every? odd? [1 3])` | `true` |
| `some` | `(some even? [1 3])` | `nil` |
| `remove` | `(remove odd? [1 2 3 4])` | `(2 4)` |
| `distinct` | `(distinct [3 1 3 2 1])` | `(3 1 2)` |
| `partition` | `(partition 2 1 [1 2 3])` | `((1 2) (2 3))` |
| `take-while` | `(take-while pos? [3 1 -1 5])` | `(3 1)` |
| `drop-while` | `(drop-while neg? [-2 -1 0 1])` | `(0 1)` |
| `interleave` | `(interleave [1 2] [:a :b])` | `(1 :a 2 :b)` |
| `interpose` | `(interpose 0 [1 2 3])` | `(1 0 2 0 3)` |
| `zipmap` | `(zipmap [:a] [1 2])` | `{:a 1}` |
| `group-by` | `(group-by odd? [1 3])` | `{true [1 3]}` |
| `sort` | `(sort [3 1 2])` | `(1 2 3)` |
| `sort-by` | `(sort-by count ["aaa" "b" "cc"])` | `(b cc aaa)` |
| `last` | `(last [1 2 3])` | `3` |
| `butlast` | `(butlast [1 2 3])` | `(1 2)` |
| `second` | `(second [1 2 3])` | `2` |
