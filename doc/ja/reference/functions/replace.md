# replace

`(replace sequence-1 sequence-2 &key start1 end1 start2 end2)`

`sequence-2`（`:start2`/`:end2` で範囲指定）の要素を `sequence-1`（`:start1`/`:end1` で範囲指定）にコピーし、その結果を返します。コピーされる要素数は 2 つの範囲長のうち小さい方です。文字列シーケンスに対応しています（cl-who が必要とするケース）。文字列は不変な値であるため、`replace` は `sequence-1` をその場で書き換えるのではなく、新しい文字列を返します。`--no-gc` を除くすべてのバックエンドで利用できます。

```lisp
(replace (make-string 5 :initial-element #\a) "XY" :start1 1) ; => "aXYaa"
```
