# slot-boundp

`(slot-boundp instance 'slot-name)`

インスタンスのクラスが指定した名前のスロットを持つかどうかを返します。lite 版: スロットは常に初期化される (デフォルト nil) ため独立した unbound 状態はなく、クラスが定義するすべてのスロットに対して `t` を返します ([`slot-makunbound`](slot-makunbound.md) は unbind ではなく nil を格納します)。

現時点では**インタープリタのみ**でサポートされます。JVM / WASM コンパイラは未対応です。

```lisp
(defclass point () ((x :initarg :x)))
(slot-boundp (make-instance 'point :x 1) 'x) ; => t
```
