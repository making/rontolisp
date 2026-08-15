# package-shadowing-symbols

`(package-shadowing-symbols package)`

常に `nil` を返します。rontolisp にシンボルのシャドーイングはありません。[`defpackage`](../special-forms/defpackage.md) の `:shadow` 節は*解決*のために名前を記録するだけでシャドーイング用のシンボルを作らず、実行時の `shadow` / `shadowing-import` は存在しません。designator の検査は行うため、存在しないパッケージは [`package-name`](package-name.md) と同様にエラーになります。

```lisp
(package-shadowing-symbols :cl-user) ; => NIL
```
