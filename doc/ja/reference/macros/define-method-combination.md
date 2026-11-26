# define-method-combination

`(define-method-combination name [:operator op] [:identity-with-one-argument b] [:documentation d])`

`(define-method-combination name lambda-list (group...) [(:arguments parameter...)] [(:generic-function var)] declaration... [documentation] form...)`

メソッドコンビネーションを定義します。[`defgeneric`](../special-forms/defgeneric.md) は `(:method-combination name argument...)` でこれを選びます。そのもとでは [`defmethod`](../special-forms/defmethod.md) のラムダリストより前にあるアトムはすべてメソッドの修飾子です。コンビネーションは総称関数より先に定義してください。

長形式の form は実効メソッドの form を計算します。`lambda-list` は `:method-combination` の評価されない引数に、各グループの変数はそのグループの適用可能なメソッド(特定的なものが先)に束縛されて実行されます。グループは `(variable selector... [:order form] [:required form] [:description string])` です。メソッドは最初にマッチしたグループに入ります。セレクタは、マッチさせる修飾子のリスト(`*` は要素としても末尾としても何にでもマッチします)、`*`、または修飾子を受け取る述語の名前です。`:order` は `:most-specific-first` か `:most-specific-last` に評価され、`:required` が真でグループにメソッドがないと、その総称関数を呼び出したところでシグナルします。返す form の中で `(call-method m (next...))` はメソッド `m` を次メソッド付きで呼び出します。次メソッドの要素は `(make-method form)` でもかまいません。`(:arguments parameter...)` は各変数を総称関数の該当する引数に評価される form に、`&rest` の変数を残りのリストのものに束縛します。

```lisp
(define-method-combination collecting (&optional (order :most-specific-first))
  ((around (:around))
   (primary (collecting) :order order :required t))
  (let ((form `(list ,@(mapcar (lambda (m) `(call-method ,m)) primary))))
    (if around
        `(call-method ,(first around) (,@(rest around) (make-method ,form)))
        form)))
(defgeneric parts (x) (:method-combination collecting :most-specific-last))
(defmethod parts collecting ((x number)) :number)
(defmethod parts collecting ((x integer)) :integer)
(defmethod parts :around ((x integer)) (list :around (call-next-method)))
(parts 1) ; => (:AROUND (:NUMBER :INTEGER))
```

短形式は同じことを、修飾子が `name` であるすべてのメソッドの `(call-method m)` を演算子 `op`(既定は `name`)に渡す形で行います。`:identity-with-one-argument` が真なら、メソッドが 1 つのときはそれだけを呼び出します。

```lisp
(define-method-combination total :operator + :identity-with-one-argument t)
(defgeneric weight (x) (:method-combination total))
(defmethod weight total ((x number)) 1)
(defmethod weight total ((x integer)) 10)
(list (weight 5) (weight 1.5)) ; => (11 1)
```

form は総称関数のディスパッチャを生成するときに、区別する引数の形ごとに実行されます。コンパイルバックエンドではプログラムのコンパイル中なので、呼び出す関数はその時点で定義されている必要があります。そこでのメソッドオブジェクトが答えるのは `method-qualifiers` だけです。[`add-method`](../functions/add-method.md) で実行時に追加したメソッドはこの総称関数には加わりません。同じグループに同じスペシャライザのメソッドが 2 つあっても受け付けます(SBCL は総称関数を呼び出したところでシグナルします)。
