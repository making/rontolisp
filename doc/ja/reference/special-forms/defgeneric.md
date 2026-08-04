# defgeneric

`(defgeneric name (param...) option...)`

総称関数を定義し、名前シンボルを返します。メソッドは [`defmethod`](defmethod.md) で追加します。指定子は**任意の**必須引数に書け、呼び出しは最も特定的なマッチするメソッドを実行します（引数は左優先でランク付け）。マッチするメソッドがなければエラーを通知します。`defgeneric` は省略可能で、最初の `defmethod` が暗黙に総称関数を作りますが、`defgeneric` を書くと全メソッドが一致すべきラムダリストを宣言できます。総称関数は通常の関数なので `#'name` や `funcall` が使えます。 `name` には setf 関数名 `(setf reader)` も書けます（[defmethod](defmethod.md) 参照）。

ラムダリストは必須引数の後に `&optional`/`&rest` を続けられ（ディスパッチャは末尾を選択されたメソッドへ転送します）、インラインの `(:method [qualifier] (param...) body...)` 節で `defgeneric` 内にメソッドを定義できます。`(:documentation "...")` は記録して無視されます。

`(:method-combination NAME [:most-specific-first | :most-specific-last])` は CLHS の**短形式**メソッド結合 — `progn`、`and`、`or`、`+`、`list`、`nconc`、`append`、`max`、`min` — を選択します。この場合、有効メソッドは「結合名を修飾子に持つ適用可能なすべてのメソッド」をその演算子で結合したものになり、順序は特定的なものから先（`:most-specific-last` を指定すると逆順）です。`:around` メソッドは通常どおり結合結果全体を包みますが、`:before`/`:after` は CLHS の規定どおり拒否されます。プライマリメソッドは結合名を修飾子として持たなければなりません: `(defmethod encode-slots progn ((o point)) ...)`。

```lisp
(defclass shape () ())
(defclass square (shape) ())
(defgeneric describe-parts (x) (:method-combination list))
(defmethod describe-parts list ((x shape)) 'shape)
(defmethod describe-parts list ((x square)) 'square)
(describe-parts (make-instance 'square)) ; => (SQUARE SHAPE)
```

ライトサブセット: 総称関数のラムダリストの `&key`、`define-method-combination`（長形式）と残りのオプションはエラーです。

```lisp
(defgeneric area (shape)
  (:documentation "The area of a shape."))
(defmethod area (shape) 0)
(defmethod area ((shape (eql :unit-square))) 1)
(list (area :unit-square) (area :dot) (funcall #'area :unit-square)) ; => (1 0 1)
```

適用可能なメソッドがない総称関数の呼び出しはエラー（`No applicable method: G on INTEGER`）を通知するため、ここでは実行例ではなく静的に示します:

```console
(defgeneric g (x))
(g 1)
```
