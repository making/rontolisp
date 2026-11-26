# cerror

`(cerror continue-format-control datum arg...)`

[`error`](error.md) と同じ条件指定子 (condition designator) の形で、**継続可能な**エラーを通知します: `datum` はフォーマット制御文字列(`arg...` はフォーマット引数)か、コンディションクラス名(`arg...` は initarg)です。シグナルの周囲には `continue-format-control` で説明される `continue` リスタートが確立されるため、[`handler-bind`](handler-bind.md) ハンドラ — あるいはシグナル点で実行される任意のコード — が [`continue`](../functions/continue.md) を呼ぶ(または `continue` リスタートを `invoke-restart` する)と、`cerror` は `nil` を返して実行はその先へ再開します。誰もリスタートを起動しなければ `cerror` は `error` とまったく同じ動作です: キャッチされなければ中断し、外側の [`handler-case`](handler-case.md) には捕捉されます。

```lisp
(handler-bind ((error (lambda (c) (continue))))
  (list :after (cerror "Ignore the error." "bad value: ~a" 42))) ; => (:AFTER NIL)
```

キャッチされなければ `error` 同様に中断します(静的な例):

```console
(cerror "Ignore the error." "bad value: ~a" 42)
(cerror "Skip this character." 'bad-input :position 7)
```
