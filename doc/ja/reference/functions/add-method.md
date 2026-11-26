# add-method

`(ensure-generic-function name &key lambda-list generic-function-class method-class argument-precedence-order)`
`(add-method generic-function method)`
`(remove-method generic-function method)`
`(find-method generic-function qualifiers specializers &optional (errorp t))`
`(method-qualifiers method)`
`(compute-applicable-methods generic-function arguments)`

総称関数とメソッドの**メタオブジェクト**(AMOP 5.3–5.4)です。`ensure-generic-function` は名前の総称関数メタオブジェクトを返します。これは `standard-generic-function` のファンカラブルなインスタンスで、`:generic-function-class` が指すクラス、またはその名前の [`defgeneric`](../special-forms/defgeneric.md) が `(:generic-function-class c)` で指すクラスがあればそのインスタンスです。最初の要求で作られて保持され、呼び出すと総称関数を呼び出し、以後は計算された名前の `fdefinition` がこれを返します。総称関数を持たない名前には新しい総称関数が作られ、プログラムは実行時に定義された関数と同じくその名前で呼び出します。

`add-method` は総称関数にメソッドメタオブジェクトを加え、総称関数を返します。修飾子と特化子が同じメソッドは置き換えます。メソッドは `standard-method`(または総称関数のメソッドクラスのインスタンス)で、`make-instance` に初期化引数 `:qualifiers`、`:specializers`(必須引数ごとに 1 つの、クラスメタオブジェクト、`(closer-mop:intern-eql-specializer object)`、`(find-class t)` のリスト)、`:lambda-list`、`:function` を渡して作ります。`:function` は引数リストと次のメソッドのリストを受け取る関数で、次のメソッドはその `closer-mop:method-function` で呼び出せます。以後このメソッドは [`defmethod`](../special-forms/defmethod.md) のメソッドと同じく、特化子に従って標準メソッド結合のすべての呼び出しに加わります。`remove-method` はこれを取り除き、`find-method` は修飾子と特化子(クラス名、クラスメタオブジェクト、`(eql object)`)でメソッドを探します。`add-method` と `remove-method` は総称関数なので、プログラムはこれらにメソッドを定義できます。

`defgeneric` が `:generic-function-class` か `:method-class` を指す総称関数への `defmethod` は、定義したメソッドのメタオブジェクトで `add-method` に届きます。そのためユーザーの `add-method` メソッドはそれを受け取ります(contextl はこれでレイヤーのキャッシュを消去します)。closer-mop の `generic-function-name`、`generic-function-methods`、`generic-function-method-class`、`generic-function-lambda-list`、`generic-function-argument-precedence-order`、`method-specializers`、`method-lambda-list`、`method-function`、`method-generic-function`、`eql-specializer-object` はメタオブジェクトを読みます。closer-mop の `ensure-method` はラムダ式からメソッドを作って追加し、その本体は `call-next-method` と `next-method-p` を呼べます。メソッドの関数は [`compile`](compile.md) で作るため、コンパイルパスでは、プログラムがクラスやメソッドを定義する途中で作るラムダ式はコンパイル済みコードになり、それ以外は評価されます。

```lisp
(defclass animal () ((name :initarg :name :reader animal-name)))
(defclass dog (animal) ())
(defgeneric speak (x))
(defmethod speak ((x animal)) (list :animal (animal-name x)))
(add-method (ensure-generic-function 'speak)
            (make-instance 'standard-method
                           :qualifiers () :lambda-list '(x)
                           :specializers (list (find-class 'dog))
                           :function (lambda (args next-methods)
                                       (declare (ignore next-methods))
                                       (list :dog (animal-name (first args))))))
(list (speak (make-instance 'dog :name "rex"))
      (speak (make-instance 'animal :name "cat"))) ; => ((:DOG "rex") (:ANIMAL "cat"))
```

`compute-applicable-methods` は、引数リストに適用できる総称関数のメソッドをメタオブジェクトで返します。もっとも特定的なものが先頭で、同じメソッドにはいつも同じオブジェクトを返します。実行済みの `defmethod` フォームが定義したメソッドも `add-method` が加えたメソッドも含みます。受け取るのは総称関数のメタオブジェクトかその `#'name` で、総称関数でない関数には `nil` を返します。これも総称関数なのでプログラムはメソッドを定義できますが、総称関数の呼び出しはこれを参照しません。そのようなメソッドが変えるのは `compute-applicable-methods` の呼び出し側に見える結果だけです。コンパイルされたプログラムがこれらのメタオブジェクトを持つのは、`compute-applicable-methods` に言及する場合です。

```lisp
(defgeneric size (x))
(defmethod size ((x number)) :number)
(defmethod size :around ((x integer)) (call-next-method))
(defmethod size (x) :anything)
(mapcar #'method-qualifiers (compute-applicable-methods #'size (list 1))) ; => ((:AROUND) NIL NIL)
(mapcar (lambda (method) (class-name (first (closer-mop:method-specializers method))))
        (compute-applicable-methods #'size (list "s"))) ; => (T)
```

## バックエンドサポート

4 つのバックエンドすべてで同じように振る舞います。コンパイルバックエンドでは、プログラムが定義する総称関数の `#'name` とリテラルの `(fdefinition 'name)` はメタオブジェクトではなくコンパイルされた関数を返します。サポート外: `defmethod` が定義したメソッドの `remove-method` はメタオブジェクトを取り除くだけでメソッドは残り、短形式のメソッド結合を持つ総称関数は実行時に加えたメソッドを無視します。`generic-function-methods` が `defmethod` の定義したメソッドを含むのは、`defgeneric` がメタオブジェクトのクラスを指す総称関数だけです。
