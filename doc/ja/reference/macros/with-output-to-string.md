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

ストリーム引数に `nil` を渡すことは、引数を省略することと同じ意味です。これは
「生の標準出力」ではなく `*standard-output*` 指定子です。そのため、自分の
optional 引数をそのまま転送するレンダラーという Common Lisp でよくある形が、
リダイレクト下でも期待どおりに動きます。

```lisp
(progn
  (defun emit (x &optional stream) (princ x stream))
  (with-output-to-string (*standard-output*)
    (emit "forwarded"))) ; => "forwarded"
```

同じ形で `*error-output*` を束縛すると、[`warn`](warn.md) のレポートを捕捉できます。
この変数のデフォルト値はプロセスの標準エラーなので、リダイレクトされていない警告が
標準出力に混ざることはありません。
