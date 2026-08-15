# with-standard-io-syntax

`(with-standard-io-syntax form...)`

`*package*` を `cl-user` に束縛し、本体を `progn` として評価します。Common Lisp ではこのマクロはリーダー/プリンター制御変数一式を標準値に動的に再束縛し、呼び出し側の設定に依存せず本体が読み書きできるようにします。rontolisp でその一式のうち実行時の値を持ち再束縛の対象になるのは `*package*` だけです(本体の `intern`/`read` は Common Lisp と同様 `cl-user` に intern します)。`*read-default-float-format*` は情報提供用(すべての浮動小数点数は唯一の double 表現を共有します)、`*print-circle*` と `*readtable*` はそれらを読むライブラリコードがロードできるように存在するだけです。`*print-escape*`/`*print-readably*` は存在し標準値 (`t`/`nil`) を保持します — `*print-escape*` は rontolisp が実際に束縛する唯一のプリンター変数で、[`print-object`](../functions/print-object.md) メソッド呼び出しの周りで束縛されるため、メソッドは [`prin1`](../functions/prin1.md) と [`princ`](../functions/princ.md) を区別できます。残りのプリンターモード変数(`*print-base*` など)は標準値を保持し、リーダー変数(`*read-base*` など)はそもそも存在しません。これは常に標準値であることと同じです。

相違点: プリンターが実際に参照する `*print-escape*`/`*print-readably*`/`*print-pretty*` はここでは再束縛されないため、このフォームの外側でそれらを非標準値に束縛していると本体に漏れます。

```lisp
(with-standard-io-syntax
  (prin1-to-string (list 1 2 3))) ; => "(1 2 3)"
(with-standard-io-syntax *package*) ; => :CL-USER
```
