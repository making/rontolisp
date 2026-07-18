# defstruct

`(defstruct name slot...)`

`name` という名前の構造体型を定義し、名前のシンボルを返します。各 `slot` はシンボルまたは `(slot-name default)` で、`default` はスロットが指定されなかった場合に構築時に評価されます（スコープ内の変数を参照できます）。このフォームは通常の関数を生成します:

- `make-name (&key slot...)` — コンストラクタ。スロットはキーワード引数で指定し、未知のキーワードはエラー
- `name-p (object)` — 型述語。この構造体のインスタンスに対してのみ `t`
- `copy-name (object)` — 浅いコピーを作るコピー関数
- `name-slot (object)` — スロットごとのアクセサ。アクセサは `setf` 可能な place でもあり、`(name-slot obj)` への `setf`/`incf`/`push` が使えます

生成される名前は通常の関数なので、第一級の値として使えます（`#'point-x`、`mapcar`、`funcall`）。コンパイル経路では `defstruct` はトップレベルフォームとしてのみサポートされます。インタープリタでは REPL や `load` 経由でも利用できます。[ユーザー定義パッケージ](../packages.md#ユーザー定義パッケージdefpackage)の下では、生成される名前はそのパッケージの内部シンボル（`geo::make-pt`）としてインターンされます。生成される名前を `defpackage` の `:export` clause に列挙することはサポートされません。

インスタンスはタグ付きリストとして表現されるため、`print` はその表現を表示します（標準の `#S(...)` 構文にはならず、読み取りもされません）。インスタンスに対する `consp`/`listp` は `t` で、`equal` はスロット単位で比較します。オプション構文 `(defstruct (name option...) slot...)` は `(:constructor name)`、`(:conc-name prefix)`、`(:predicate name)`、`(:copier name)` をすべてのバックエンドでサポートし、スロットの前のドキュメント文字列は受理されて破棄されます。BOA コンストラクタ — `(:constructor name (lambda-list))` — はライト形式でサポートされます: ラムダリストに名前があるスロットはそのパラメータを読み、それ以外のスロットはコンストラクタ本体で initform を評価します。スロットオプション `:type` と `:read-only` はパースされて無視されます。`:include` による継承はサポートされません。構造体名は [`defmethod`](defmethod.md) のパラメータ specializer として使用できます。また、コンパイル済みプログラムのランタイム `eval` は `defstruct` もアクセサの `setf` place も認識しません（生成された関数を `eval` から呼び出すことは可能です）。

```lisp
(defstruct point x (y 10))
(setq p (make-point :x 1))
(list (point-x p) (point-y p) (point-p p) (point-p '(1 2))) ; => (1 10 t nil)
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
