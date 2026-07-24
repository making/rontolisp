# string< string> string<= string>= string/= string-lessp string-greaterp string-not-greaterp string-not-lessp string-not-equal

`(string< string1 string2 &key start1 end1 start2 end2)` -- `(string> ...)` -- `(string<= ...)` -- `(string>= ...)` -- `(string/= ...)` -- `(string-lessp ...)` -- `(string-greaterp ...)` -- `(string-not-greaterp ...)` -- `(string-not-lessp ...)` -- `(string-not-equal ...)`

2 つの文字列を辞書順で比較し、関係が成り立つ場合は不一致位置のインデックス（真の値）を、成り立たない場合は `nil` を返します。インデックスは最初に異なる文字の **`string1` 内の**位置です。比較対象の部分文字列が等しい場合は `end1` になり、これが `string<=` / `string>=` / `string-not-greaterp` / `string-not-lessp` が等しい文字列に対して返す値です。最初の 5 つは大文字小文字を区別し、`string-lessp`、`string-greaterp`、`string-not-greaterp`、`string-not-lessp`、`string-not-equal` はそれぞれ `string<`、`string>`、`string<=`、`string>=`、`string/=` の大文字小文字を区別しない版です。各引数は `string` で強制変換されるため、シンボルや文字の指定子も受け付けます。`:start1`/`:end1`/`:start2`/`:end2` は実際に比較する部分文字列の範囲を指定しますが、返されるインデックスは `string1` 全体における絶対位置のままです。

```lisp
(list (string< "aaaa" "aaab")
      (string>= "aaaaa" "aaaa")
      (string-not-greaterp "Abcde" "abcdE")
      (string-lessp "012AAAA789" "01aaab6" :start1 3 :end1 7 :start2 2 :end2 6)) ; => (3 4 5 6)
```
