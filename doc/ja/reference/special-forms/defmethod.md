# defmethod

`(defmethod name [qualifier] (param... ) body...)`

総称関数 `name` にメソッドを追加し、名前シンボルを返します（先行する [`defgeneric`](defgeneric.md) がなければ総称関数を暗黙に作ります）。specializer は**任意の**必須引数に付けられ、`(var specializer)` と書きます:

- `(var (eql literal))` — 引数がそのリテラル（キーワード、クォートされたシンボル、数値、文字）のときにマッチ
- `(var class-name)` — [`defclass`](defclass.md) クラスとそのサブクラスのインスタンスにマッチ
- `(var struct-name)` — [`defstruct`](defstruct.md) 型のインスタンスにマッチ（ディスパッチャは構造体述語と同じインスタンスタグをテストします）
- `(var type-name)` — 組み込み型（`integer`、`float`、`number`、`string`、`symbol`、`keyword`、`character`、`cons`、`list`、`null`、`hash-table`、`function` など）にマッチ
- `(var t)` または素の `var` — デフォルトメソッド

呼び出しはマッチする最も特定的なメソッドを実行します: まず `eql` メソッド、次にクラスメソッド（サブクラスがスーパークラスより先）、次に組み込み型（`integer` のようなサブタイプが `number` のようなスーパータイプより先）、最後にデフォルトメソッドの順で、マッチがなければエラーを通知します。同じ specializer を再定義すると以前のメソッドを置き換えます。本体はドキュメント文字列と `(declare ...)` で始められます（どちらも無視されます）。

```lisp
(defclass animal () ())
(defclass dog (animal) ())
(defgeneric speak (x))
(defmethod speak ((x dog)) "woof")
(defmethod speak ((x animal)) "some sound")
(defmethod speak ((x integer)) "a number")
(defmethod speak ((x (eql :cat))) "meow")
(defmethod speak (x) "?")
(list (speak (make-instance 'dog)) (speak (make-instance 'animal))
      (speak 42) (speak :cat) (speak "s")) ; => ("woof" "some sound" "a number" "meow" "?")
```

## メソッド修飾子と `call-next-method`

ラムダリストの前に `:before`、`:after`、`:around` の**修飾子**を置くと補助メソッドを追加できます（標準メソッド結合）。1 回の呼び出しでは:

- 適用される `:around` メソッドが最も特定的なものから順に実行され、それぞれが残りをラップします。
- 次に `:before` メソッドが最も特定的なものから順に副作用として実行されます。
- 次に最も特定的な基本（修飾子なし）メソッドが実行され、その値が結果になります。
- 最後に `:after` メソッドが**最も特定的でないもの**から順に副作用として実行されます。

基本メソッドや `:around` メソッドの中では、`(call-next-method)` が次に特定的でないメソッドを呼び出し（現在の引数を渡し、`(call-next-method arg...)` と書けば新しい引数を渡します）、`(next-method-p)` はそのようなメソッドが存在するかを返します。次のメソッドがない状態で `call-next-method` を呼ぶとエラーになります。

```lisp
(defclass point () ((x :initarg :x :accessor px)))
(defclass point3d (point) ((z :initarg :z :accessor pz)))
(defgeneric describe-point (p))
(defmethod describe-point ((p point)) (list :x (px p)))
(defmethod describe-point ((p point3d)) (append (call-next-method) (list :z (pz p))))
(defmethod describe-point :around ((p point)) (list :point (call-next-method)))
(describe-point (make-instance 'point3d :x 1 :z 3)) ; => (:point (:x 1 :z 3))
```

ライトサブセット: `&key` はエラー、可変長総称関数の `call-next-method` は必須引数のみを転送し、標準メソッド結合はクラスメソッドとデフォルトメソッドについてサポートされます（`eql` や組み込み型の specializer を持つ `:around`/`:before`/`:after` は、同じ specializer の基本メソッドとデフォルトメソッドのみと結合します）。コンパイルパスでは `defmethod` はトップレベルフォームとしてのみサポートされ、コンパイルされたプログラムのメソッド集合はコンパイル時に固定されます。
