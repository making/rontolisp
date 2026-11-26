# define-modify-macro

`(define-modify-macro name (parameter...) function [documentation])`

`(name place argument...)` が `(setf place (function place argument...))` に展開されるマクロ `name` を定義します。これは `function` を通じた `place` の read-modify-write です。末尾の `&rest` パラメータは呼び出しにスプライスされます。簡易版: `place` の部分式は複数回評価される可能性があり (`get-setf-expansion` による単一評価プロトコルはありません)、省略可能なドキュメント文字列は無視されます。

```lisp
(define-modify-macro maxf (lo) max) ; => MAXF
```
