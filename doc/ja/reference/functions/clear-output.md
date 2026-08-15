# clear-output

`(clear-output &optional stream)`

指定された出力ストリームがバッファに溜めていてまだ書き出していない内容を捨て、nil を返します。どのバックエンドもプログラムから捨てられる形で出力をバッファしません (書き込みはその場で下位の出力先へ届きます) ので、この関数はストリーム指定子を検証するだけで何もしません。存在する理由は [Gray プロトコル](../../guides/gray-streams.md) が `stream-clear-output` を定めており、ポータブルなストリームクラスがそれを実装するからです。Gray インスタンスに対する呼び出しはそのメソッドに届きます。

```lisp
(progn (princ "kept") (clear-output)) ; => NIL
```
