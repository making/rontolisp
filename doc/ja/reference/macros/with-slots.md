# with-slots

`(with-slots (slot-or-pair...) instance body...)`

CLOS サブセットインスタンス([`defclass`](../special-forms/defclass.md) / [`define-condition`](define-condition.md))のスロット値を本体のために変数に束縛します: 各エントリはスロット名、または `var` をスロット `slot` に束縛する `(var slot)` ペアです。インスタンスフォームは一度だけ評価されます。[`slot-value`](slot-value.md) 読み出しの `let` に展開されます。

lite(読み取り専用): Common Lisp の `with-slots` はシンボルマクロを束縛するため代入がスロットに書き戻されますが、ここでの束縛はただの変数なので、`setq`/`setf` はローカル変数への代入にしかなりません。コンディションの `:report` ラムダなど、主要な読み取り用途をカバーします。

```lisp
(defclass ws-point () ((x :initarg :x) (y :initarg :y)))
(with-slots (x (why y)) (make-instance 'ws-point :x 3 :y 4) (list x why)) ; => (3 4)
```
