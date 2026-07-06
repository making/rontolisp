# make-instance

`(make-instance 'class-name :initarg value ...)`

[`defclass`](../special-forms/defclass.md) クラスのインスタンスを生成します。スロットは `:initarg` キーワード（省略時はスロット名のキーワード）で与え、与えられなかったスロットは `:initform`（なければ `nil`）になります。クラス名は `defclass` で定義済みのクラスを指す**リテラルのクォートされたシンボル**でなければなりません — 実行時のクラステーブルは存在しないため、計算されたクラス名はエラーです。`format` と同様に `make-instance` は関数値を持たないマクロなので、`#'make-instance` はサポートされません。

```lisp
(defclass point () ((x :initarg :x :initform 0 :reader point-x)
                    (y :initarg :y :initform 0 :reader point-y)))
(setq p (make-instance 'point :x 3))
(list (point-x p) (point-y p)) ; => (3 0)
```
