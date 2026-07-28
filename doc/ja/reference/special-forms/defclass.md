# defclass

`(defclass name (superclass?) ((slot slot-option...) ...) class-option...)`

クラスを定義し、名前シンボルを返します。これは **静的な CLOS サブセット**です。スーパークラスは最大 1 つ（単一継承）で、インスタンスは [`make-instance`](../macros/make-instance.md) で生成される第一級のオブジェクトです（[`defstruct`](defstruct.md) のインスタンスと同様にリストではなく、`consp` は `nil`、`print` は `#<NAME :SLOT value ...>` と表示します）。スロットオプション:

- `:initarg keyword` — コンストラクタで使うキーワード（省略時はスロット名のキーワード）
- `:initform expr` — スロットが与えられなかったときに生成時に評価されるデフォルト値。**省略するとスロットは未束縛のまま**になります（CL と同じ）: [`slot-boundp`](../macros/slot-boundp.md) は `nil` を返し、読み取りは `unbound-slot` をシグナルします
- `:reader fn` — `fn` を読み取り関数として定義
- `:accessor fn` — `:reader` と同様で、さらに `setf` 可能な place になります

サブクラスはスーパークラスの全スロットを継承し、そのインスタンスはスーパークラスに対する [`defmethod`](defmethod.md) のクラス specializer にマッチします。サブクラスは継承したスロットを再宣言できます: 記憶域は継承した 1 つのスロットのままで、サブクラスの `:initform`/`:initarg` が継承分を上書きし、reader/accessor は継承分に追加されます。reader/accessor は通常の defun なので第一級関数です。クラスオプションは `(:documentation "...")`（受理して無視）と `(:default-initargs :initarg value ...)`（[`make-instance`](../macros/make-instance.md) が未指定の initarg に適用するデフォルト）をサポートし、スロットオプション `:type` は記録されます（チェックはなし）。その他のクラスオプション、その他のスロットオプション（`:allocation`、`:writer` など）、多重継承はエラーです。コンパイルパスでは `defclass` はトップレベルフォームとしてのみサポートされ、実行時のクラス操作のうち存在するのは（リテラルの対象クラスを取る）[`change-class`](../macros/change-class.md) だけで、`find-class` やクラス再定義はありません。

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
