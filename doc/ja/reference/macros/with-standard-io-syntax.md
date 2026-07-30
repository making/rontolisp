# with-standard-io-syntax

`(with-standard-io-syntax form...)`

本体を `progn` として評価します。Common Lisp ではこのマクロはリーダー/プリンター制御変数一式を標準値に動的に再束縛し、呼び出し側の設定に依存せず本体が読み書きできるようにしますが、rontolisp には再束縛すべきものがありません。`*package*` はプログラム実行前に解決され実行時のセルではなく、`*read-default-float-format*` は情報提供用(すべての浮動小数点数は唯一の double 表現を共有します)、`*print-circle*` と `*readtable*` はそれらを読むライブラリコードがロードできるように存在するだけです。`*print-escape*`/`*print-readably*` は存在し標準値 (`t`/`nil`) を保持します — `*print-escape*` は rontolisp が実際に束縛する唯一のプリンター変数で、[`print-object`](../functions/print-object.md) メソッド呼び出しの周りで束縛されるため、メソッドは [`prin1`](../functions/prin1.md) と [`princ`](../functions/princ.md) を区別できます。残りの標準変数 — `*print-base*`、`*read-base*` など — はそもそも存在しません。これは常に標準値であることと同じです。

したがってこのマクロは恒等ラッパーであり、これを使うポータブルな Common Lisp コードがそのままロードされて動くように提供されています。

```lisp
(with-standard-io-syntax
  (prin1-to-string (list 1 2 3))) ; => "(1 2 3)"
```
