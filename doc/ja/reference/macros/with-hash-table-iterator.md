# with-hash-table-iterator

`(with-hash-table-iterator (name hash-table) body...)`

`name` をローカル関数(CL の `macrolet` ではなく `flet`)に束縛します。呼ぶたびに 1 エントリを `(values t key value)` で返し、尽きると `(values nil nil nil)` を返します。通常のローカル関数なので、CL の `macrolet` 束縛と違い値として渡すこともできます。

ハッシュテーブルは開始時にスナップショットされるため、走査中の追加・削除は見えません(CLHS でも未定義の場合です)。順序は不定です。

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) 1)
  (with-hash-table-iterator (next h)
    (multiple-value-bind (morep k v) (next)
      (list morep k v)))) ; => (T A 1)
```
