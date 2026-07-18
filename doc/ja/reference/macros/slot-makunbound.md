# slot-makunbound

`(slot-makunbound instance 'slot-name)`

lite 版: スロットに nil を格納し (rontolisp に独立した unbound 状態はありません)、インスタンスを返します。

JVM / WASM コンパイラではスロット名はクオートされたシンボルリテラルである必要があります ([`slot-value`](slot-value.md) と同様)。実行時に計算されるスロット名はインタープリタのみで動作します。

```lisp
(defclass point () ((x :initarg :x)))
(let ((p (make-instance 'point :x 1)))
  (slot-makunbound p 'x)
  (slot-value p 'x)) ; => nil
```
