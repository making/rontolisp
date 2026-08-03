# use-value

`(use-value value [condition])`

最内のアクティブな `use-value` リスタートを `value` を渡して起動します。アクティブなものがなければ `nil` を返します([`continue`](continue.md) と同様、エラーにはなりません)。[`handler-bind`](../macros/handler-bind.md) ハンドラからこれを呼ぶと、対応する [`restart-case`](../macros/restart-case.md) 節へ `value` を引数として制御が移ります — シグナル地点で値を差し替えるためにライブラリが使うイディオムです(trivia のパターン展開器はこの方法で guard テストをリフトします)。

```lisp
(define-condition needs-value () ())
(handler-bind ((needs-value (lambda (c) (use-value 42))))
  (restart-case (progn (signal 'needs-value) :not-restarted)
    (use-value (v) (list :used v)))) ; => (:USED 42)
```
