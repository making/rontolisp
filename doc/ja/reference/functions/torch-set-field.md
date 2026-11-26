# torch:set-field

`(torch:set-field module name value)`

モジュールの指定フィールドを設定し (存在しなければ追加し)、モジュール自身を返します。パラメータをこの方法で差し替えると、レイヤーを特定の重みに束縛できます。テストや例でレイヤーの出力を再現可能にするのがこの用途です。

```lisp
(defparameter *lin* (torch:linear 3 2))
(torch:set-field *lin* :weight (torch:parameter '((1.0 0.0) (0.0 1.0) (1.0 1.0))))
(torch:set-field *lin* :bias (torch:parameter '(0.5 -0.5)))
(torch:data (torch:forward *lin* (torch:tensor '((1.0 2.0 3.0))))) ; => #f((4.5 4.5))
```
