# abort

`(abort [condition])`

最内のアクティブな `abort` リスタートを起動します。rontolisp は独自の `abort` リスタートを確立しない(デバッガ REPL がない)ため、これが届くのはプログラム側がその名前で確立したリスタートだけです。アクティブなものがなければ CL の契約どおりエラーを通知します。

```lisp
(restart-case (progn (abort) :not-reached)
  (abort () :aborted)) ; => :ABORTED
```
