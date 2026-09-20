# clear-input

`(clear-input &optional stream)`

指定された入力ストリームがバッファに溜めていてまだ渡していない内容を捨て、nil を返します。どのバックエンドもプログラムから捨てられる形で入力をバッファしません (読み込みはその場で下位の入力元に届きます) ので、この関数はストリーム指定子を検証するだけで何もしません。読み込み側の [clear-output](clear-output.md) にあたります。nil と t はどちらも標準入力ストリームを指します。

```lisp
(with-input-from-string (s "abc") (clear-input s)) ; => NIL
```
