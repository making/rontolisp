# copy-pprint-dispatch set-pprint-dispatch pprint-dispatch

`(copy-pprint-dispatch &optional table)` -- `(set-pprint-dispatch type-specifier function &optional priority table)` -- `(pprint-dispatch object &optional table)`

プリティプリント・ディスパッチテーブルです。ある型の値をどう印字するかを表す `(type-specifier function priority)` の集まりで、`copy-pprint-dispatch` は `*print-pprint-dispatch*` の新しい複製（引数に `nil` を渡したときは空の初期テーブルの複製）を返し、`set-pprint-dispatch` はエントリを追加（`function` が `nil` なら削除）し、`pprint-dispatch` はオブジェクトに一致する最も優先度の高いエントリ関数と、見つかったかどうかを表す第 2 の値を返します。エントリ関数は `(funcall function stream object)` として呼ばれます。

通常の印字操作（`print`、`princ`、`prin1`、`~A`、`~S`）はこのテーブルを参照**しません**。エントリが効くのは、プログラム自身がエントリ関数を呼ぶ箇所です。

```lisp
(let ((table (copy-pprint-dispatch)))
  (set-pprint-dispatch 'integer (lambda (stream x) (princ (* 2 x) stream)) 0 table)
  (with-output-to-string (out) (funcall (pprint-dispatch 21 table) out 21))) ; => "42"
```
