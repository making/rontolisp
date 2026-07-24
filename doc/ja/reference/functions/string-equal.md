# string-equal

`(string-equal string1 string2 &key start1 end1 start2 end2)`

2 つの文字列を大文字小文字を無視して 1 文字ずつ比較し、一致するとき `t` を、そうでなければ `nil` を返します。大文字小文字の畳み込みは ASCII 規則に従うため、`"ABC"` と `"abc"` は等しくなります。大文字小文字を区別する比較には `string=` を使用してください。`:start1`/`:end1`/`:start2`/`:end2` は実際に比較する部分文字列の範囲を指定します。

```lisp
(list (string-equal "ABC" "abc") (string-equal "TOGETHER" "frog" :start1 1 :end1 3 :start2 2)) ; => (T T)
```
