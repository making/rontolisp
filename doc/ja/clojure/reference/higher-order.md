# 高階関数

関数に対する関数です。合成・部分適用・否定・キャッシュ・トランポリン・並置・nil 補完・述語の組み合わせ・キーによる最大最小。どれも関数値なので
`map`・`filter`・`apply` は裸の名前を取ります。

| Name | Example | Result |
|---|---|---|
| `comp` | `((comp inc inc) 5)` | `7` |
| `partial` | `((partial + 10) 5)` | `15` |
| `complement` | `((complement odd?) 4)` | `true` |
| `constantly` | `((constantly 7) 1 2)` | `7` |
| `identity` | `(map identity [1 2])` | `(1 2)` |
| `memoize` | `(let [f (memoize inc)] [(f 1) (f 1)])` | `[2 2]` |
| `trampoline` | `(trampoline (fn [x] (if (zero? x) :done (fn [] 0))) 0)` | `:done` |
| `juxt` | `((juxt inc dec) 5)` | `[6 4]` |
| `fnil` | `((fnil inc 0) nil)` | `1` |
| `every-pred` | `((every-pred odd? pos?) 1 3)` | `true` |
| `some-fn` | `((some-fn :a :b) {:b 2})` | `2` |
| `min-key` | `(min-key count "ab" "c")` | `"c"` |
| `max-key` | `(max-key count "ab" "c")` | `"ab"` |
