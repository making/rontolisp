# make-instance

`(make-instance 'class-name :initarg value ...)`

[`defclass`](../special-forms/defclass.md) クラスのインスタンスを生成します。スロットは `:initarg` キーワード（省略時はスロット名のキーワード）で与え、与えられなかったスロットは `:initform`（なければ `nil`）になります。`defclass` で定義済みのクラスを指す**リテラルのクォートされたクラス名**はコンストラクタの直接呼び出しにコンパイルされます。計算されたクラスも使えます — 実行時に作られた名前シンボル（`(make-instance (intern (format nil "~A-~A" style '#:reporter) package) ...)`。`pkg:name` と `pkg::name` のどちらの綴りでも一致するので、クラスをエクスポートする必要はありません）や [`find-class`](../functions/find-class.md) が返すメタオブジェクト、そして値としての `#'make-instance` も同様で、いずれもプログラムが定義するクラス群に対して実行時にディスパッチされます。コンパイルされたバックエンドではそのクラス集合はコンパイル時に固定されます：実行時データから作ったクラスは存在しません。

```lisp
(defclass point () ((x :initarg :x :initform 0 :reader point-x)
                    (y :initarg :y :initform 0 :reader point-y)))
(setq p (make-instance 'point :x 3))
(list (point-x p) (point-y p)) ; => (3 0)
```
