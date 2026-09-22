# shadow

`(shadow symbol-names &optional package)`

各名前(文字列指示子、またはそのリスト)を `package`(省略時はカレントパッケージ)の**シャドーイングシンボル**にします。その名前のシンボルがすでにパッケージに存在すれば(所有・インポートを問わず)そのまま印を付け、なければまず新しい自前のシンボルとしてインターンします。以降、use しているパッケージが同名のシンボルをエクスポートしていても [`find-symbol`](find-symbol.md) はこのシンボルをステータス `:internal` で返し、[`package-shadowing-symbols`](package-shadowing-symbols.md) に列挙されます。`t` を返します。[`defpackage`](../special-forms/defpackage.md) の `:shadow` 節の実行時版です。

パッケージはこの記録をメンバーテーブルに保持するため、すべてのバックエンドで動作します。インタプリタは生きたレジストリに書き込み、コンパイル系バックエンドはプログラムが [`make-package`](make-package.md) で作ったパッケージのテーブルに書き込みます。読み込み/コンパイル時パッケージ(組み込み、およびコンパイルされたプログラムの `defpackage` 産物)はそこでは凍結されており、何も変えずに `t` を返します。

```lisp
(make-package :sh-base :use nil)
(export (intern "X" :sh-base) :sh-base)
(make-package :sh-user :use '(:sh-base))
(multiple-value-list (find-symbol "X" :sh-user)) ; => (SH-BASE::X :INHERITED)
(shadow "X" :sh-user) ; => T
(multiple-value-list (find-symbol "X" :sh-user)) ; => (SH-USER::X :INTERNAL)
(package-shadowing-symbols :sh-user) ; => (SH-USER::X)
```
