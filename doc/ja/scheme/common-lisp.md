# Common Lisp との混在

Scheme の識別子は大文字小文字を保つので、Common Lisp 側からはエスケープした名前で
Scheme の手続きを呼びます。1 回だけ定義され `set!` されないトップレベル手続きは、
通常の関数です。

```console
$ cat lib.scm
(define (twice x) (* 2 x))
$ cat main.lisp
(load "lib.scm")
(print (|twice| 21))
$ rontolisp main.lisp
42
```

この方向で注意が要る値は 3 つだけです: `'()` は `NIL`、`#t` は `T`、`#f` は独自の値です --
Common Lisp のコードに渡した `#f` はそこでは真であり、Scheme に返った `NIL` は空リスト、
つまり真です。
