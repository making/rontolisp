# defsetf

`(defsetf access-fn update-fn)`

`access-fn` に対する `setf` 展開を登録します。上記の **短形式** は `(setf (access-fn args...) value)` を `(update-fn args... value)` に展開します。**長形式** `(defsetf access-fn (lambda-list) (store-var...) body...)` は本体を展開時に評価し(ラムダリストには引数フォーム用の一時変数が、ストア変数には新しい値の一時変数が束縛されます)、ストアフォームを返す必要があります。5 値の完全なプロトコルが必要な場合は [`define-setf-expander`](define-setf-expander.md) を使ってください。すべてのバックエンドで動作します。

```lisp
(defun ref-value (box) (car box))
(defsetf ref-value rplaca)
(let ((box (list 1)))
  (setf (ref-value box) 42)
  box) ; => (42)
```
