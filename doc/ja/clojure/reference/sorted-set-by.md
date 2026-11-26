# sorted-set-by

`(sorted-set-by comparator x ...)`

`clojure.core/sorted-set-by`: `comparator` の順に並べたソート済みセットです。`comparator` の
答えは `sorted-map-by` と同じく読みます（数値は整数部の符号、真偽値は「より小さい」）。
`comparator` が等しいとした要素は1つにまとまり、先の要素が残ります。どの操作も比較関数を
引き継ぎます。値としては比較関数と要素を取る関数です。

```clojure
(println (sorted-set-by > 1 3 2))                                ; #{3 2 1}
(prn (sorted-set-by #(compare (count %1) (count %2)) "ab" "c" "de")) ; #{"c" "ab"}
```
