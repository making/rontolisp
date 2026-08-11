# replace

`(replace sequence-1 sequence-2 &key start1 end1 start2 end2)`

`sequence-2`（`:start2`/`:end2` で範囲指定）の要素を `sequence-1`（`:start1`/`:end1` で範囲指定）にコピーし、その結果を返します。コピーされる要素数は 2 つの範囲長のうち小さい方です。`sequence-1` がベクタの場合 — [`make-string`](make-string.md) や [`make-array`](make-array.md) の `:element-type 'character` で確保した文字列を含む — CL と同様にその場で書き換えられ、そのまま返されます。そのため「バッファを確保し、書き込み、返す」というイディオムはすべてのバックエンドで動作します。`sequence-1` がリストの場合も同様に、コンスセルを通じてその場で書き換えられます。文字列リテラルも `sequence-1` として使えます（cl-who が必要とするケース）が、コンパイル系バックエンドでは不変な値であるため、`replace` はその場で書き換えるのではなく新しい文字列を返します（インタープリタはその場で書き換えます）。`--no-gc` を除くすべてのバックエンドで利用できます。

```lisp
(replace (make-string 5 :initial-element #\a) "XY" :start1 1) ; => "aXYaa"
```
