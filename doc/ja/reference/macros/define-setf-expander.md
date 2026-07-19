# define-setf-expander

`(define-setf-expander name lambda-list body...)`

`(setf (name args...) value)` の展開方法を定義します。本体(通常はバッククォートを使うフォーム生成器)は展開時に実行され、ラムダリストにプレースの引数フォームが束縛された状態で、setf 展開の 5 値を [`values`](../functions/values.md) で返す必要があります: 一時変数、その値フォーム、ストア変数、ストアフォーム、アクセスフォームです。`&environment` パラメータは受け付けられ nil に束縛され、サブプレースの展開には [`get-setf-expansion`](../functions/get-setf-expansion.md) が利用できます。すべてのバックエンドで動作します(コンパイラは展開器をコンパイル時に実行します)。`setf` テンプレート内のシンボルは [`defmacro`](../special-forms/defmacro.md) と同様に定義側パッケージで解決されます。

```lisp
(defun my-first (x) (first x))
(define-setf-expander my-first (place)
  (let ((store (gensym)))
    (values '() '() (list store)
            `(progn (rplaca ,place ,store) ,store)
            `(my-first ,place))))
(let ((lst (list 1 2 3)))
  (setf (my-first lst) 99)
  lst) ; => (99 2 3)
```
