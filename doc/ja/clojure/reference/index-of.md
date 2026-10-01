# clojure.string/index-of

`(clojure.string/index-of s sub)`
`(clojure.string/index-of s sub from)`

リテラル文字列 `sub` が `s` の `from`（デフォルト 0）以降に最初に現れるインデックスを返し、なければ `-1` を返します。関数値としても動きます。

Deviation: 見つからない `sub` は `-1` を返します。oracle の `clojure.string/index-of` は `nil` を返します。

```clojure
(println (clojure.string/index-of "hihi" "i")) ; 1
(println (clojure.string/index-of "hihi" "i" 2)) ; 3
(println (clojure.string/index-of "hi" "z")) ; -1
```
