# defclass

`(defclass name (superclass?) ((slot slot-option...) ...) class-option...)`

クラスを定義し、名前シンボルを返します。これは **静的な CLOS サブセット**です。スーパークラスは最大 1 つ（単一継承）で、インスタンスは [`make-instance`](../macros/make-instance.md) で生成されるタグ付きリストとして表現されます（[`defstruct`](defstruct.md) のインスタンスと同様に `consp` を満たし、`print` はそのリスト表現を表示します）。スロットオプション:

- `:initarg keyword` — コンストラクタで使うキーワード（省略時はスロット名のキーワード）
- `:initform expr` — スロットが与えられなかったときに生成時に評価されるデフォルト値（省略時は `nil`。unbound 状態はありません）
- `:reader fn` — `fn` を読み取り関数として定義
- `:accessor fn` — `:reader` と同様で、さらに `setf` 可能な place になります

サブクラスはスーパークラスの全スロットを継承し（継承したスロットの再定義はエラー）、そのインスタンスはスーパークラスに対する [`defmethod`](defmethod.md) のクラス specializer にマッチします。reader/accessor は通常の defun なので第一級関数です。クラスオプションは `(:documentation "...")`（受理して無視）と `(:default-initargs :initarg value ...)`（[`make-instance`](../macros/make-instance.md) が未指定の initarg に適用するデフォルト）をサポートし、スロットオプション `:type` は記録されます（チェックはなし）。その他のクラスオプション、その他のスロットオプション（`:allocation`、`:writer` など）、多重継承はエラーです。コンパイルパスでは `defclass` はトップレベルフォームとしてのみサポートされ、実行時のクラス操作（`find-class`、`change-class`、クラス再定義）はありません。

```lisp
(defclass animal () ((name :initarg :name :accessor animal-name)))
(defclass dog (animal) ((breed :initarg :breed :initform "mixed" :reader dog-breed)))
(setq d (make-instance 'dog :name "Rex"))
(list (animal-name d) (dog-breed d)) ; => ("Rex" "mixed")
```

```lisp
(defclass counter () ((n :initform 0 :accessor counter-n)))
(setq c (make-instance 'counter))
(incf (counter-n c))
(setf (counter-n c) (+ (counter-n c) 10))
(counter-n c) ; => 11
```
