# force-output

`(force-output &optional stream)`

指定された出力ストリームのバッファに溜まったバイトを下位の出力先へ書き出し、nil を返します。引数なし (または `nil`/`t`) の場合は標準出力を書き出します。ここでは `finish-output` も同じ操作です。書き出しさえ済めば rontolisp の書き込みはすべて同期的であり、それ以上待つものがないためです。ソケットへの書き込みはバッファされません。例外は WASM `--component` バックエンドの TLS ストリームで、書き込みを溜めておき、書き出し・読み込み・クローズのときに送ります ([`rontolisp:tls-upgrade`](rontolisp-tls-upgrade.md#backend-support) を参照)。それ以外のソケットに対する書き出しは何もしませんが、書いておいても損はありません。

```lisp
(progn (princ "no newline yet") (force-output)) ; => NIL
```
