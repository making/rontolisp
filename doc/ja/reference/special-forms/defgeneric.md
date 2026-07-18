# defgeneric

`(defgeneric name (param...) option...)`

総称関数を定義し、名前シンボルを返します。メソッドは [`defmethod`](defmethod.md) で追加します。指定子は**任意の**必須引数に書け、呼び出しは最も特定的なマッチするメソッドを実行します（引数は左優先でランク付け）。マッチするメソッドがなければエラーを通知します。`defgeneric` は省略可能で、最初の `defmethod` が暗黙に総称関数を作りますが、`defgeneric` を書くと全メソッドが一致すべきラムダリストを宣言できます。総称関数は通常の関数なので `#'name` や `funcall` が使えます。

ラムダリストは必須引数の後に `&optional`/`&rest` を続けられ（ディスパッチャは末尾を選択されたメソッドへ転送します）、インラインの `(:method [qualifier] (param...) body...)` 節で `defgeneric` 内にメソッドを定義できます。`(:documentation "...")` は記録して無視されます。

ライトサブセット: 総称関数のラムダリストの `&key`、`:method-combination` と残りのオプションはエラーです。

```lisp
(defgeneric area (shape)
  (:documentation "The area of a shape."))
(defmethod area (shape) 0)
(defmethod area ((shape (eql :unit-square))) 1)
(list (area :unit-square) (area :dot) (funcall #'area :unit-square)) ; => (1 0 1)
```

適用可能なメソッドがない総称関数の呼び出しはエラー（`No applicable method: g`）を通知するため、ここでは実行例ではなく静的に示します:

```console
(defgeneric g (x))
(g 1)
```
