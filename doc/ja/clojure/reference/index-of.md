# clojure.string/index-of

`(clojure.string/index-of s sub)`
`(clojure.string/index-of s sub from)`

リテラル文字列 `sub` が `s` の `from`（デフォルト 0）以降に最初に現れるインデックスを返し、なければ `nil` を返します。関数値としても動きます。`from` は Java の `indexOf` と同じように読みます。負の値は 0、末尾を超える値では何も見つからず（空の `sub` は末尾で見つかります）、小数は切り捨てます。

```clojure
(println (clojure.string/index-of "hihi" "i")) ; 1
(println (clojure.string/index-of "hihi" "i" 2)) ; 3
(println (clojure.string/index-of "hi" "z")) ; nil
(println (clojure.string/index-of "hihi" "i" -1)) ; 1
```
