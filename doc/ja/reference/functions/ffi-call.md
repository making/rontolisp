# ffi:call

`(ffi:call address return-type argument-types args...)`

`address` にある C 関数を、指定した戻り値型と引数型リストで呼び出し、
各引数と結果をマーシャリングする。呼び出し規約は実行時に決まる --- だからこそ
`cffi:defcfun` はプログラム中で新しい形を作り出せる。型指定子は CFFI のキーワード
(`:char` 〜 `:ullong`、`:float`、`:double`、`:pointer`、`:string`、`:void`、
`:int8` 〜 `:uint64`) に加え、値渡し構造体の `(:struct member...)` と可変長引数の
開始位置を示す `:varargs`。

```lisp
(ffi:call (ffi:symbol (ffi:open) "strlen") :long '(:string) "hello")
; => 5
```

`:string` 引数は呼び出しのために外部メモリへコピーされ、呼び出し後に解放される。`:string` の戻り値は NUL 終端の UTF-8 を読み戻す。
