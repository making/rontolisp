# slot-boundp

`(slot-boundp instance 'slot-name)`

インスタンスのクラスが指定した名前のスロットを持つかどうかを返します。lite 版: スロットは常に初期化される (デフォルト nil) ため独立した unbound 状態はなく、クラスが定義するすべてのスロットに対して `t` を返します ([`slot-makunbound`](slot-makunbound.md) は unbind ではなく nil を格納します)。

JVM / WASM コンパイラではスロット名はクオートされたシンボルリテラルである必要があります ([`slot-value`](slot-value.md) と同様)。実行時に計算されるスロット名はインタープリタのみで動作します。

```lisp
(defclass point () ((x :initarg :x)))
(slot-boundp (make-instance 'point :x 1) 'x) ; => t
```
