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
| `bigint` | `(bigint 7/2)` | `3` |
| `biginteger` | `(biginteger 7/2)` | `3` |
| `bigdec` | `(bigdec 0.5)` | `1/2` |
| `rationalize` | `(rationalize 0.1)` | `1/10` |
| `numerator` | `(numerator 6/4)` | `3` |
| `denominator` | `(denominator 6/4)` | `2` |
| `boolean` | `(boolean 1)` | `true` |
| `char` | `(char 97)` | `a` |
| `rand` | `(rand 5)` | a double in `[0,5)` |
| `rand-int` | `(rand-int 1)` | `0` |
| `unchecked-add` | `(unchecked-add 9223372036854775807 1)` | `-9223372036854775808` |
| `unchecked-subtract` | `(unchecked-subtract -9223372036854775808 1)` | `9223372036854775807` |
| `unchecked-multiply` | `(unchecked-multiply 4611686018427387904 4)` | `0` |
| `unchecked-inc` | `(unchecked-inc 9223372036854775807)` | `-9223372036854775808` |
| `unchecked-dec` | `(unchecked-dec -9223372036854775808)` | `9223372036854775807` |
| `unchecked-negate` | `(unchecked-negate -9223372036854775808)` | `-9223372036854775808` |
| `unchecked-add-int` | `(unchecked-add-int 2147483647 1)` | `-2147483648` |
| `unchecked-subtract-int` | `(unchecked-subtract-int -2147483648 1)` | `2147483647` |
| `unchecked-multiply-int` | `(unchecked-multiply-int 65536 65536)` | `0` |
| `unchecked-inc-int` | `(unchecked-inc-int 2147483647)` | `-2147483648` |
| `unchecked-dec-int` | `(unchecked-dec-int -2147483648)` | `2147483647` |
| `unchecked-negate-int` | `(unchecked-negate-int -2147483648)` | `-2147483648` |
| `unchecked-divide-int` | `(unchecked-divide-int -7 2)` | `-3` |
| `unchecked-remainder-int` | `(unchecked-remainder-int -7 2)` | `-1` |
| `unchecked-int` | `(unchecked-int 2147483648)` | `-2147483648` |
| `unchecked-long` | `(unchecked-long 7/2)` | `3` |
| `unchecked-short` | `(unchecked-short 70000)` | `4464` |
| `unchecked-byte` | `(unchecked-byte 200)` | `-56` |
| `unchecked-char` | `(unchecked-char 97)` | `a` |
| `unchecked-double` | `(unchecked-double 1)` | `1.0` |
| `unchecked-float` | `(unchecked-float 1/2)` | `0.5` |
