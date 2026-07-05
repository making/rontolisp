# with-output-to-string

`(with-output-to-string (stream) body...)`

`stream` を文字列出力ストリームに束縛して本体フォームを評価し、ストリームに書き込まれた内容全体を文字列として返します。`princ`、`prin1`、`print`、`terpri`、`write-line`、`write-string` はオプションの stream 引数として、`format` は destination としてこのストリームを受け取り、各呼び出しがストリームに追記します。3 つすべてのバックエンドで動作します。

```lisp
(with-output-to-string (s)
  (princ "1 + 2 = " s)
  (princ (+ 1 2) s)) ; => "1 + 2 = 3"
```
