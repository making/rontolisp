# assert

`(assert test-form [(place...) [datum args...]])`

`test-form` を評価し、偽であればエラーをシグナルします。真であれば nil を返します。省略可能な datum と引数は `error` の制御文字列と引数のように働き、デフォルトの「The assertion ... failed.」メッセージを置き換えます。これは Common Lisp の `assert` のライト版です。リスタートシステムが存在しないため、place のリストは受理されますが無視されます（対話的な再格納ループはありません）。

```lisp
(let ((x 1)) (assert (> x 0)) x) ; => 1
```

アサーションに失敗すると実行が中断されるため、静的な形で示します。

```console
(let ((x 0)) (assert (> x 0) (x) "x must be positive, got ~a" x))
; error: x must be positive, got 0
```
