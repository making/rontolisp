# string=

`(string= string1 string2 &key start1 end1 start2 end2)`

2 つの文字列を 1 文字ずつ比較し、完全に等しいとき `t` を、そうでなければ `nil` を返します。比較は大文字小文字を区別するため、`"abc"` と `"ABC"` は等しくありません。大文字小文字を区別しないテストには `string-equal` を使用してください。`:start1`/`:end1`/`:start2`/`:end2` は実際に比較する部分文字列の範囲を指定します。

```lisp
(list (string= "abc" "abc") (string= "together" "frog" :start1 1 :end1 3 :start2 2)) ; => (T T)
```
