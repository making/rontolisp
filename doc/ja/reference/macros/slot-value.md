# slot-value

`(slot-value object 'slot-name)`

[`defclass`](../special-forms/defclass.md) インスタンスのスロットを読み取ります。`setf` 可能な place でもあります。スロット名は**リテラルのクォートされたシンボル**でなければならず、コンパイル/展開時にスロットの固定位置へ解決されます。そのため計算されたスロット名はエラーで、無関係な 2 つのクラスが同じスロット名を*異なる*位置で使っている場合は曖昧として拒否されます（1 つの継承チェーン内では位置は常に一致します。クラスごとに定義される `:accessor`/`:reader` 関数の使用を推奨します）。読み取りはオブジェクトの型を検査しません（`defstruct` アクセサと同様）。

```lisp
(defclass user () ((name :initarg :name)))
(setq u (make-instance 'user :name "Alice"))
(setf (slot-value u 'name) (concatenate 'string (slot-value u 'name) "!"))
(slot-value u 'name) ; => "Alice!"
```
