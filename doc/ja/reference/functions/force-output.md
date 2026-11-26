# force-output

`(force-output &optional stream)`

指定された出力ストリームのバッファに溜まったバイトを下位の出力先へ書き出し、nil を返します。引数なし (または `nil`/`t`) の場合は標準出力を書き出します。ここでは `finish-output` も同じ操作です。書き出しさえ済めば rontolisp の書き込みはすべて同期的であり、それ以上待つものがないためです。ソケットは書き込みを溜めておき、書き出したとき、プログラムがソケットを待つときや終わるとき、クローズしたときに送ります ([`rontolisp:tcp-connect`](rontolisp-tcp-connect.md) を参照)。

```lisp
(progn (princ "no newline yet") (force-output)) ; => NIL
```
