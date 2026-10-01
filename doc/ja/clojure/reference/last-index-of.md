# clojure.string/last-index-of

`(clojure.string/last-index-of s sub)`
`(clojure.string/last-index-of s sub from)`

リテラル文字列 `sub` が `s` に最後に現れるインデックスを返し、なければ `-1` を返します。関数値としても動きます。

```clojure
(println (clojure.string/last-index-of "hihi" "i")) ; 3
```
