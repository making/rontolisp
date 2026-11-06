# formatter

`(formatter control-string)`

ストリームと引数を受け取る関数を作ります。この関数は [`format`](format.md) と同じく `control-string` を引数で展開してストリームへ書き、制御文字列が消費しなかった引数を返します。`control-string` は評価されないリテラル文字列です。

関数はそれ自体が制御文字列として使えます。`format` と `~?` 指示子は、その関数をストリームと引数で呼び出します。

```lisp
(let ((f (formatter "<~a>")))
  (list (format nil f 1) (format nil "~?|" f '(2)))) ; => ("<1>" "<2>|")
```
