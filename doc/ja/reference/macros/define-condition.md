# define-condition

`(define-condition name (parent...) (slot...) option...)`

コンディション型を、組み込みコンディション階層 `condition` > `serious-condition` > `error`(> `simple-error` および標準エラーサブタイプ)と `warning` の上の CLOS サブセットクラス([`defclass`](../special-forms/defclass.md) 参照)として定義します。親のデフォルトは `condition` です。親が複数ある場合、**最初の**親がスロットレイアウトを提供し(単一継承)、残りの親は `typep`/`typecase`/`handler-case` の型マッチングにのみ参加します(それらのスロットは継承されません)。スロットは `defclass` サブセット(`:initarg`/`:initform`/`:reader`/`:accessor`、および破棄される `:documentation`)を使います。クラスオプションのうち `(:report x)`(リテラル文字列または `(lambda (condition stream) ...)`)はコンディションの**レポート**です。[`error`](error.md)/[`signal`](signal.md)/[`warn`](warn.md) でシグナルされたときのメッセージであると同時に、[`princ`](../functions/princ.md)・[`princ-to-string`](../functions/princ-to-string.md)・[`format`](format.md) の `~A` がそのコンディションオブジェクトに対して出力するテキストでもあります。[`prin1`](../functions/prin1.md) / `~S` は影響を受けず、`#<TYPE :SLOT value ...>` のインスタンス構文のままです。`:report` を定義しない型は親のものを継承します。どの祖先にも `:report` がなく `simple-condition` 系のスロット(`format-control`/`format-arguments`、つまり `simple-error`/`simple-warning`/`simple-condition` のサブタイプ)を持つ型は、それらに `format` を適用した結果をレポートとします。どちらも持たない型は `princ` でも `#<...>` 表現のままです。型に [`print-object`](../functions/print-object.md) メソッドがあれば、どちらのエスケープモードでもレポートより優先されます。`(:default-initargs :initarg value ...)` は生成されるクラスに転送され(未指定の initarg に `make-condition`/型付き `error` がデフォルトを適用)、`(:documentation ...)` は破棄されます。型名を返します。コンパイルパスでは `defclass` と同じくトップレベル専用フォームです。

```lisp
(define-condition my-parse-error (error)
  ((input :initarg :input :reader my-parse-error-input))
  (:report "input did not parse")) ; => MY-PARSE-ERROR
```

レポートは `princ`/`~A` が出力するもので、`prin1`/`~S` は引き続きインスタンスを表示します:

```lisp
(define-condition dc-report-demo (error)
  ((input :initarg :input :reader dc-report-demo-input))
  (:report (lambda (c s) (format s "did not parse: ~a" (dc-report-demo-input c)))))
(list (princ-to-string (make-condition 'dc-report-demo :input "x"))
      (prin1-to-string (make-condition 'dc-report-demo :input "x")))
; => ("did not parse: x" "#<DC-REPORT-DEMO :INPUT \"x\">")
```

```console
CL-USER> (error 'my-parse-error :input "x")
Error: input did not parse
```
