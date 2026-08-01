# defclass

`(defclass name (superclass?) ((slot slot-option...) ...) class-option...)`

クラスを定義し、名前シンボルを返します。これは **静的な CLOS サブセット**です。スーパークラスは最大 1 つ（単一継承）で、インスタンスは [`make-instance`](../macros/make-instance.md) で生成される第一級のオブジェクトです（[`defstruct`](defstruct.md) のインスタンスと同様にリストではなく、`consp` は `nil`、`print` は `#<NAME :SLOT value ...>` と表示します）。スロットオプション:

- `:initarg keyword` — コンストラクタで使うキーワード（省略時はスロット名のキーワード）
- `:initform expr` — スロットが与えられなかったときに生成時に評価されるデフォルト値。**省略するとスロットは未束縛のまま**になります（CL と同じ）: [`slot-boundp`](../macros/slot-boundp.md) は `nil` を返し、読み取りは `unbound-slot` をシグナルします
- `:reader fn` — `fn` を読み取り関数として定義
- `:accessor fn` — `:reader` と同様で、さらに `setf` 可能な place になります

サブクラスはスーパークラスの全スロットを継承し、そのインスタンスはスーパークラスに対する [`defmethod`](defmethod.md) のクラス specializer にマッチします。サブクラスは継承したスロットを再宣言できます: 記憶域は継承した 1 つのスロットのままで、サブクラスの `:initform`/`:initarg` が継承分を上書きし、reader/accessor は継承分に追加されます。reader/accessor は通常の defun なので第一級関数です。クラスオプションは `(:documentation "...")`（受理して無視）と `(:default-initargs :initarg value ...)`（[`make-instance`](../macros/make-instance.md) が未指定の initarg に適用するデフォルト）をサポートし、スロットオプション `:type` は記録されます（チェックはなし）。`:metaclass`（後述）がない場合、その他のクラスオプション、その他のスロットオプション（`:allocation`、`:writer` など）、多重継承はエラーです。コンパイルパスでは `defclass` はトップレベルフォームとしてのみサポートされ、[`find-class`](../functions/find-class.md) と [`class-of`](../functions/class-of.md) はクラスメタオブジェクトを返します。実行時のクラス操作のうち存在するのは（リテラルの対象クラスを取る）[`change-class`](../macros/change-class.md) だけで、クラス再定義はありません。

**メタクラス**（静的な MOP サブセット）: クラスオプション `(:metaclass M)` には、先に `defclass` で定義された `standard-class` を継承するクラスを指定します。クラス定義は**定義時**にクラス定義プロトコルを実行します: メタクラスがインスタンス化され（その `shared-initialize` メソッドは、他の未知のクラスオプションを「オプションの残り部分のリスト」を値とする initarg として受け取ります）、各スロットの非標準オプション（`:col-type` など）は initarg として `closer-mop:direct-slot-definition-class` に渡され、その答えのクラスがそのスロットの direct-slot-definition メタオブジェクトとしてインスタンス化されます。実効スロットは `closer-mop:compute-effective-slot-definition` を通して計算され（デフォルトメソッドは、ユーザーのオーバーライドの `call-next-method` の動的スコープの内側で `closer-mop:effective-slot-definition-class` を選んでインスタンス化します）、`closer-mop:finalize-inheritance` は**即時に**実行されます（CL は遅延ファイナライズですが、入力は静的なので定義時エラーのタイミングだけが異なります）。以後 `find-class` と `class-of` はメタクラスのインスタンスを返し、クラス自体のインスタンスは通常のオブジェクトのままです。プロトコルは静的です: トップレベル以外の位置の `defclass` や、定義時に未知のクラスに対するプロトコル呼び出しはエラーをシグナルします。

```lisp
(defclass animal () ((name :initarg :name :accessor animal-name)))
(defclass dog (animal) ((breed :initarg :breed :initform "mixed" :reader dog-breed)))
(setq d (make-instance 'dog :name "Rex"))
(list (animal-name d) (dog-breed d)) ; => ("Rex" "mixed")
```

```lisp
(defclass shape () ((sides :initform 0 :reader sides) (label :initarg :label)))
(defclass square (shape) ((sides :initform 4 :accessor square-sides)))
(let ((s (make-instance 'square)))
  (list (sides s) (square-sides s) (slot-boundp s 'label))) ; => (4 4 NIL)
```

```lisp
(defclass counter () ((n :initform 0 :accessor counter-n)))
(setq c (make-instance 'counter))
(incf (counter-n c))
(setf (counter-n c) (+ (counter-n c) 10))
(counter-n c) ; => 11
```

```lisp
(defclass table-class (standard-class) ((table-name)))
(defmethod closer-mop:validate-superclass ((c table-class) (s standard-class)) t)
(defmethod shared-initialize :before ((c table-class) slots &key table-name &allow-other-keys)
  (if table-name (setf (slot-value c 'table-name) (car table-name)) nil))
(defclass account () ((id :initarg :id)) (:metaclass table-class) (:table-name "accounts"))
(list (class-name (find-class 'account))
      (slot-value (find-class 'account) 'table-name)
      (typep (find-class 'account) 'table-class)) ; => (ACCOUNT "accounts" T)
```
