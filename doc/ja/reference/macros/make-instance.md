# make-instance

`(make-instance 'class-name :initarg value ...)`

[`defclass`](../special-forms/defclass.md) クラスのインスタンスを生成します。スロットは `:initarg` キーワード（省略時はスロット名のキーワード）で与え、与えられなかったスロットは `:initform`（なければ `nil`）になります。どのスロットも宣言していない initarg も受け付けるので、`initialize-instance` メソッドがキーワードとして受け取る initarg は、クラスにスロットがあってもなくてもそのメソッドに届きます。`defclass` で定義済みのクラスを指す**リテラルのクォートされたクラス名**はコンストラクタの直接呼び出しにコンパイルされます。`initialize-instance` か `shared-initialize` の primary・`:before`・`:around` メソッドが適用されるクラスは、代わりに CLHS 7.1 のとおりに初期化されます: スロットは unbound で始まり、システムの `shared-initialize` メソッドが受け取った initarg（メソッドが別の initarg を渡すこともあります）でスロットを埋め、なお unbound のスロットを `:initform` で埋めます。このときスロットを埋めるのは、そのスロットが宣言した initarg だけです。計算されたクラスも使えます — 実行時に作られた名前シンボル（`(make-instance (intern (format nil "~A-~A" style '#:reporter) package) ...)`。`pkg:name` と `pkg::name` のどちらの綴りでも一致するので、クラスをエクスポートする必要はありません）や [`find-class`](../functions/find-class.md) が返すメタオブジェクト、そして値としての `#'make-instance` も同様で、いずれもプログラムが定義するクラス群に対して実行時にディスパッチされます。コンパイルされたバックエンドではそのクラス集合はコンパイル時に固定されます：実行時データから作ったクラスは存在しません。

```lisp
(defclass point () ((x :initarg :x :initform 0 :reader point-x)
                    (y :initarg :y :initform 0 :reader point-y)))
(setq p (make-instance 'point :x 3))
(list (point-x p) (point-y p)) ; => (3 0)
```
