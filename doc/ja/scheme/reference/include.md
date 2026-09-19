# include

`(include "file"...)`

各ファイルを読み、その内容を `include` の位置に `begin` として置きます: トップレベルではその定義はプログラム自身の定義に、本体の先頭では内部定義になります。相対ファイル名は `include` を書いたファイルのディレクトリからの相対で、REPL では作業ディレクトリからの相対です。ファイルはプログラムと同時に読まれるので、コンパイルしたプログラムはその内容を持ち、実行時には何も読みません。自分自身をインクルードするファイルと、マクロが展開した結果の `include` は拒否されます。

```scheme
; file: greet.scm
(define (greet name) (string-append "hello, " name))
```

```scheme
(include "greet.scm")
(display (greet "world"))
(newline)
```

```
hello, world
```
