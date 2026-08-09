# defstruct

`(defstruct name slot...)`

`name` という名前の構造体型を定義し、名前のシンボルを返します。各 `slot` はシンボルまたは `(slot-name default)` で、`default` はスロットが指定されなかった場合に構築時に評価されます（スコープ内の変数を参照できます）。このフォームは通常の関数を生成します:

- `make-name (&key slot...)` — コンストラクタ。スロットはキーワード引数で指定し、未知のキーワードはエラー
- `name-p (object)` — 型述語。この構造体のインスタンスに対してのみ `t`
- `copy-name (object)` — 浅いコピーを作るコピー関数
- `name-slot (object)` — スロットごとのアクセサ。アクセサは `setf` 可能な place でもあり、`(name-slot obj)` への `setf`/`incf`/`push` が使えます

生成される名前は通常の関数なので、第一級の値として使えます（`#'point-x`、`mapcar`、`funcall`）。コンパイル経路では `defstruct` はトップレベルフォームとしてのみサポートされます。インタープリタでは REPL や `load` 経由でも利用できます。[ユーザー定義パッケージ](../packages.md#ユーザー定義パッケージdefpackage)の下では、生成される名前はそのパッケージの内部シンボル（`geo::make-pt`）としてインターンされます。生成される名前を `defpackage` の `:export` clause に列挙することはサポートされません。

インスタンスはリストではなく第一級の構造体オブジェクトです。`print` は標準の `#S(NAME :SLOT value ...)` 構文で表示します。インスタンスに対する `consp`/`listp` は `nil` で、`equal` はスロット単位で比較します（Common Lisp では異なる構造体は `equal` になりません）。オプション構文 `(defstruct (name option...) slot...)` は `(:constructor name)`、`(:conc-name prefix)`、`(:predicate name)`、`(:copier name)`、`(:include parent (slot new-default) ...)`、`(:type (vector ...))`、`(:print-object fn)`、`(:print-function fn)` をすべてのバックエンドでサポートし、スロットの前のドキュメント文字列は受理されて破棄されます。BOA コンストラクタ — `(:constructor name (lambda-list))` — はライト形式でサポートされます: ラムダリストに名前があるスロットはそのパラメータを読み、それ以外のスロットはコンストラクタ本体で initform を評価します。スロットオプション `:type` と `:read-only` はパースされて無視されます。構造体名は [`defmethod`](defmethod.md) のパラメータ specializer として使用できます。また、コンパイル済みプログラムのランタイム `eval` は `defstruct` もアクセサの `setf` place も認識しません（生成された関数を `eval` から呼び出すことは可能です）。

`(:include parent)` は構造体の単一継承です。親のスロットが先に並ぶため、親のアクセサ・親の述語・`(typep x 'parent)` はいずれも子のインスタンスに対して機能し、子は自分のスロットをその後ろに追加します。末尾のスロット上書き — `(:include parent (slot new-default) ...)` — は継承したスロットのデフォルトを**この子のレイアウトでのみ**差し替えます（親自身のデフォルトは変わりません）。スロットのインデックスは継承したままなので、親のアクセサからそのまま読めます。親が定義していないスロットを上書きしようとするとエラーです。`(:type (vector ...))` は「インスタンス」を構造体オブジェクトではなく素のベクタにします。要素型は無視され（rontolisp のベクタは要素型を持ちません）、アクセサは `aref` 読み出しと `setf` 可能な place になり、コピーアは `copy-seq` です。構造体タグを持たないため、この型には述語も `#S(...)` 構文もなく、`defmethod` の specializer にもできません（Common Lisp も同様です — 型指定された構造体は `structure-object` ではありません）。`:type` 構造体への `:include` はエラーです。

`(:print-object fn)` と `(:print-function fn)` は、`#S(...)` 構文の代わりに構造体専用のプリンタを与えます。`fn` は関数指定子（シンボルまたは `lambda` 式）で、`:print-object` は `(object stream)`、古い綴りの `:print-function` は `(object stream depth)` で呼び出します。`depth` は常に `0` です（プリントレベルは追跡していません）。どちらも構造体型に対する [`print-object`](defmethod.md) メソッドそのものなので、すべての出力操作 — `print`、`princ`、`prin1`、`format` の `~A`/`~S` — がこれを使い、後から同じ型に `defmethod print-object` を書けば置き換わります。本体には [`print-unreadable-object`](../macros/print-unreadable-object.md) を使うのが通例です。両方のオプションを与えるのはエラーで、どちらかを `:type` と併用するのもエラーです（型指定された構造体は素のベクタであり、ディスパッチする型を持ちません）。

```lisp
(defstruct (celsius (:print-object (lambda (obj stream)
                                     (format stream "~D deg" (celsius-c obj)))))
  (c 0))
(list (princ-to-string (make-celsius :c 21)) (format nil "~A" (make-celsius)))
; => ("21 deg" "0 deg")
```

```lisp
(defstruct shape (kind :none))
(defstruct (circle (:include shape)) (r 1))
(setq c (make-circle :kind :round :r 2))
(list (shape-kind c) (circle-r c) (shape-p c) (circle-p (make-shape))) ; => (:ROUND 2 T NIL)
```

```lisp
(defstruct point x (y 10))
(setq p (make-point :x 1))
(list (point-x p) (point-y p) (point-p p) (point-p '(1 2))) ; => (1 10 T NIL)
```

同じ `#S(NAME :SLOT value ...)` 構文は読み取りもされます。ソース中の `#S(...)` リテラルは自己評価するインスタンスなので、クォートの下でも、バッククォートテンプレートの中でも、`#(...)` ベクタリテラルの中でも使えます。`defstruct` はそれより**前の**トップレベルフォームに現れている必要があります（Common Lisp と同じく、ソースを処理していく過程でリテラルが構築されるためです）。スロットの値はデータとして読まれ評価されないので、`#S(BOX :V (+ 1 2))` はリスト `(+ 1 2)` を格納します。同じスロットが 2 回現れた場合は最も左の値が残ります。省略されたスロットは `default` を取りますが、ここでは評価される式ではなく定数である必要があります。構造体でない型名、その型に存在しないスロット名、スロット項目の個数が奇数の場合はエラーです。ランタイムの [`read`](../functions/read.md) / `read-from-string` もすべてのバックエンドでインスタンスを構築するので、`(read-from-string (prin1-to-string p))` はどこでも往復します。コンパイルされたリーダーの注意点が 1 つあります。省略されたスロットの `default` は `nil` か単純な定数なら実行時にも尊重されますが、再読取り可能な定数集合の外の `default` は誤った値を代入する代わりにシグナルします（[コンパイルされた read/load の制限](../../guides/read-load-limitations.md)を参照）。

```lisp
(defstruct point x (y 10))
(list #S(POINT :X 1 :Y 2) #S(POINT :X 7) (equal #S(POINT :X 1 :Y 2) (make-point :x 1 :y 2)))
; => (#S(POINT :X 1 :Y 2) #S(POINT :X 7 :Y 10) T)
```

```lisp
(defstruct book title (sold 0))
(setq b (make-book :title "RontoLisp"))
(incf (book-sold b))
(setf (book-title b) "RontoLisp 2e")
(setq c (copy-book b))
(incf (book-sold c))
(list (book-title b) (book-sold b) (book-sold c)) ; => ("RontoLisp 2e" 1 2)
```
