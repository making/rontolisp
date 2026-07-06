# defgeneric

`(defgeneric name (param...) option...)`

第 1 引数でディスパッチする総称関数を定義し、名前シンボルを返します。メソッドは [`defmethod`](defmethod.md) で追加します。総称関数の呼び出しは第 1 引数にマッチする最も特定的なメソッドを実行し、マッチするメソッドがなければエラーを通知します。`defgeneric` は省略可能で、最初の `defmethod` が暗黙に総称関数を作りますが、`defgeneric` を書くと全メソッドが一致すべきラムダリストを宣言できます。総称関数は通常の関数なので `#'name` や `funcall` が使えます。

ライトサブセット: ラムダリストは必須引数のみ（`&optional`/`&rest`/`&key` はエラー）で、サポートされるオプションは `(:documentation "...")`（記録して無視）だけです。`(:method ...)`、`:method-combination` などはエラーです。

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
