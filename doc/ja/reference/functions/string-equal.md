# string-equal

`(string-equal string1 string2 &key start1 end1 start2 end2)`

2 つの文字列を大文字小文字を無視して 1 文字ずつ比較し、一致するとき `t` を、そうでなければ `nil` を返します。大文字小文字の畳み込みは ASCII 規則に従うため、`"ABC"` と `"abc"` は等しくなります。大文字小文字を区別する比較には `string=` を使用してください。`:start1`/`:end1`/`:start2`/`:end2` は実際に比較する部分文字列の範囲を指定します。nil の `:end1`/`:end2` は長さを意味します。負の値、整数でない値（nil の `:start1`/`:start2` を含む）、文字列の長さを超える値、終端より後ろの開始位置は、同じ範囲に対して [`subseq`](subseq.md) が通知するのと同じ `type-error` を通知します。キーワードの値を含む引数は、呼び出しに書かれた順に 1 回ずつ評価され、キーワードが重複した場合は最初のものが使われます。

```lisp
(list (string-equal "ABC" "abc") (string-equal "TOGETHER" "frog" :start1 1 :end1 3 :start2 2)) ; => (T T)
```
