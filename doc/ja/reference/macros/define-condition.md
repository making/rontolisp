# define-condition

`(define-condition name (parent...) (slot...) option...)`

コンディション型を、組み込みコンディション階層 `condition` > `serious-condition` > `error`(> `simple-error` および標準エラーサブタイプ)と `warning` の上の CLOS サブセットクラス([`defclass`](../special-forms/defclass.md) 参照)として定義します。親のデフォルトは `condition` です。親が複数ある場合、**最初の**親がスロットレイアウトを提供し(単一継承)、残りの親は `typep`/`typecase`/`handler-case` の型マッチングにのみ参加します(それらのスロットは継承されません)。スロットは `defclass` サブセット(`:initarg`/`:initform`/`:reader`/`:accessor`、および破棄される `:documentation`)を使います。クラスオプションのうち `(:report x)`(リテラル文字列または `(lambda (condition stream) ...)`)は [`error`](error.md)/[`signal`](signal.md) でシグナルされたときのメッセージとして使われ、`(:default-initargs :initarg value ...)` は生成されるクラスに転送され(未指定の initarg に `make-condition`/型付き `error` がデフォルトを適用)、`(:documentation ...)` は破棄されます。型名を返します。コンパイルパスでは `defclass` と同じくトップレベル専用フォームです。

```lisp
(define-condition my-parse-error (error)
  ((input :initarg :input :reader my-parse-error-input))
  (:report "input did not parse")) ; => my-parse-error
```

```console
> (error 'my-parse-error :input "x")
Error: input did not parse
```
