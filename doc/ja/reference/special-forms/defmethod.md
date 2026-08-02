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

## setf メソッド

`name` には関数名 `(setf reader)` も書けます: メソッドは `reader` の *setf 関数*の一部となり、`(setf (reader arg...) value)` は新しい値を**先頭**パラメータとして（CL の setf 関数の引数順）ディスパッチします。[`defclass`](defclass.md) の `:accessor` と同名の setf メソッドは、アクセサの writer メソッドを隠すのではなくマージされます。`#'(setf reader)` は第一級関数としての writer です。`(defgeneric (setf reader) ...)` も同様に動作し、インラインの `(:method ...)` 節も使えます。

```lisp
(defclass sbox () ((v :initarg :v :reader content)))
(defmethod (setf content) (new (b sbox)) (setf (slot-value b 'v) new))
(let ((b (make-instance 'sbox :v 1)))
  (setf (content b) 42)
  (content b)) ; => 42
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

## 組み込み関数名へのメソッド定義

組み込み関数の名前（`close`、`open-stream-p`、`stream-element-type` など）にメソッドを定義すると、**その組み込み関数が総称関数のデフォルトメソッドになります**。specializer に指定したクラスのインスタンスではメソッドが実行され、それ以外の引数では組み込みの動作がそのまま残ります（最も限定的でない基本メソッドからの `(call-next-method)` も組み込みに到達します）。そのため自作のストリームクラスに `close` メソッドを定義しても、実際のファイルストリームに対する `(close stream)` は動作し続けます。ユーザーがデフォルトメソッド（specializer なし）を定義した場合は、そちらが組み込みを完全に置き換えます。

```lisp
(defclass counter () ((n :initform 3)))
(defmethod length ((c counter)) (slot-value c 'n))
(list (length (make-instance 'counter)) (length "abcd")) ; => (3 4)
```

ライトサブセット: これはすべてのバックエンドで動作します — コンパイルパスではその名前の呼び出しが生成されたディスパッチャを経由し、フォールスルーが元の組み込みになります。対象はネイティブの組み込み関数に裏付けられた名前のみです。展開として実装されている名前（`mapcar`、`sort`、`format` など）にはメソッドを定義できず、組み込み名への単純な `defun` はコンパイルパスでは引き続き無視されます。
