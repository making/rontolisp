# with-output-to-string

`(with-output-to-string (stream) body...)`

`stream` を文字列出力ストリームに束縛して本体フォームを評価し、ストリームに書き込まれた内容全体を文字列として返します。`princ`、`prin1`、`print`、`terpri`、`fresh-line`、`write-line`、`write-char`、`write-string` はオプションの stream 引数として、`format` は destination としてこのストリームを受け取り、各呼び出しがストリームに追記します。3 つすべてのバックエンドで動作します。

```lisp
(with-output-to-string (s)
  (princ "1 + 2 = " s)
  (princ (+ 1 2) s)) ; => "1 + 2 = 3"
```

束縛する変数を `*standard-output*` と名付けると、本体の実行中は stream 引数なしの印字関数ファミリー全体がリダイレクトされます -- 呼び出された関数の内部や、destination が `t` の `format` も含みます。これらの呼び出しはコール時に `*standard-output*` の現在の（動的に束縛された）値を読むためです。同じリダイレクトは、`*standard-output*` を出力ストリームに束縛する任意の `let` でも機能します。

```lisp
(progn
  (defun greet () (princ "hello"))
  (with-output-to-string (*standard-output*)
    (greet)
    (format t " ~a" 42))) ; => "hello 42"
```
