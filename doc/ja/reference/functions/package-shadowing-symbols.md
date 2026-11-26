# package-shadowing-symbols

`(package-shadowing-symbols package)`

`package` のシャドーイングシンボルを名前順に返します。[`defpackage`](../special-forms/defpackage.md) の `:shadow` 節と `:shadowing-import-from` 節が宣言した名前、および実行時に [`shadow`](shadow.md) と [`shadowing-import`](shadowing-import.md) が加えた名前で、それぞれパッケージがその名前でアクセス可能にしているシンボルの綴りで表されます。[`unintern`](unintern.md) はシンボルをこのリストから取り除きます。指示子の検査は行うため、存在しないパッケージは [`package-name`](package-name.md) と同様にエラーになります。

```lisp
(package-shadowing-symbols :cl-user) ; => NIL
```

```lisp
(defpackage :pss-base (:use) (:export :a))
(defpackage :pss-demo (:use :pss-base) (:shadow :b) (:shadowing-import-from :pss-base :a))
(package-shadowing-symbols :pss-demo) ; => (PSS-BASE:A PSS-DEMO::B)
```
