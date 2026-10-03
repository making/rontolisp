# 数値と述語

算術と比較のコア、型述語です。比較は true/false を返し、述語は nil と false を別の値として扱います。

| Name | Example | Result |
|---|---|---|
| `+` | `(+ 1 2 3)` | `6` |
| `-` | `(- 10 4)` | `6` |
| `*` | `(* 2 3 4)` | `24` |
| `/` | `(/ 7 2)` | `7/2` |
| `quot` | `(quot 7 2)` | `3` |
| `rem` | `(rem 7 2)` | `1` |
| `mod` | `(mod -7 2)` | `1` |
| `abs` | `(abs -5)` | `5` |
| `max` | `(max 3 9 4)` | `9` |
| `min` | `(min 3 9 4)` | `3` |
| `expt` | `(expt 2 10)` | `1024` |
| `inc` | `(inc 5)` | `6` |
| `dec` | `(dec 5)` | `4` |
| `=` | `(= 1 1)` | `true` |
| `not=` | `(not= 1 2)` | `true` |
| `==` | `(== 1 1.0)` | `true` |
| `<` | `(< 1 2)` | `true` |
| `>` | `(> 2 1)` | `true` |
| `<=` | `(<= 2 2)` | `true` |
| `>=` | `(>= 3 4)` | `false` |
| `nil?` | `(nil? nil)` | `true` |
| `some?` | `(some? nil)` | `false` |
| `even?` | `(even? 2)` | `true` |
| `odd?` | `(odd? 2)` | `false` |
| `zero?` | `(zero? 0)` | `true` |
| `pos?` | `(pos? -1)` | `false` |
| `neg?` | `(neg? -1)` | `true` |
| `false?` | `(false? false)` | `true` |
| `true?` | `(true? true)` | `true` |
| `boolean?` | `(boolean? false)` | `true` |
| `coll?` | `(coll? [1])` | `true` |
| `string?` | `(string? "a")` | `true` |
| `symbol?` | `(symbol? 'a)` | `true` |
| `fn?` | `(fn? inc)` | `true` |
| `instance?` | `(instance? String "a")` | `true` |
| `class` | `(class "a")` | `:string` |
| `int` | `(int 2.7)` | `2` |
| `long` | `(long -2.7)` | `-2` |
| `double` | `(double 1)` | `1.0` |
| `float` | `(float 1/2)` | `0.5` |
| `byte` | `(byte 1.9)` | `1` |
| `short` | `(short 1.9)` | `1` |
| `num` | `(num 1/2)` | `1/2` |
| `boolean` | `(boolean 1)` | `true` |
| `char` | `(char 97)` | `a` |
| `rand` | `(rand 5)` | a double in `[0,5)` |
| `rand-int` | `(rand-int 1)` | `0` |
| `unchecked-add` | `(unchecked-add 3 4)` | `7` |
