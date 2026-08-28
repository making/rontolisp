# make-hash-table

`(make-hash-table &key test size)`

新しい空のハッシュテーブルを作成して返します。キーは `equal` によるかのように構造的に比較されるため、リスト・文字列・数値・シンボル・文字のキーが値で一致します。`:test 'equalp` はこれを広げます。テーブルは各キーを配置する前に大文字小文字を区別しない代表値へ畳み込むため、`"CS"` と `"Cs"` は1つのキーになります(何が畳み込まれ何が畳み込まれないかは[データ型](../data-types.md)を参照。またコンパイル系バックエンドはテストを評価せずソースから読むため、リテラルで書いてください)。それ以外の `:test` は情報的なものにすぎず、`:size` やその他のキーワードは無視されます。エントリは `(setf (gethash key table) value)` で格納し、`gethash` で読み出します。

```lisp
(let ((h (make-hash-table :test 'equalp)))
  (setf (gethash "x" h) 1)
  (gethash "X" h)) ; => 1
```
