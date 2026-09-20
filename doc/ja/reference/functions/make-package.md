# make-package

`(make-package name &key :use :nicknames)`

実行時にパッケージを作成し、それを返す -- [`find-package`](find-package.md)
と同じ、大文字正規名のキーワードである。名前とニックネームはリーダーが
ソースを大文字化するのと同様に大文字化されるため、`:my-pkg` と `"MY-PKG"`
は同じパッケージを指す。各 `:use` 項目はプログラムが既知のパッケージ
(組込みまたは [`defpackage`](../special-forms/defpackage.md) によるもの)を
指さねばならず、新しいパッケージは空で始まる。[`intern`](intern.md)、[`export`](export.md)、[`import`](import.md)、
[`shadowing-import`](shadowing-import.md)、[`shadow`](shadow.md) がそこに入れたものは -- すべての
バックエンドで -- そのメンバーテーブルに記録されるため、[`find-symbol`](find-symbol.md)、
[`do-symbols`](../macros/do-symbols.md)、
[`with-package-iterator`](../macros/with-package-iterator.md) から見え、
[`unintern`](unintern.md) で取り除ける。

登録済みのパッケージやニックネームと衝突する名前、未知の `:use`
項目は、捕捉可能な `package-error` を signal する。読込/compile
時パッケージに影響することはない: その名前での作成は置換ではなく signal
である。

```lisp
(make-package :doc-mp :use '(:cl) :nicknames '(:dmp)) ; => :DOC-MP
(find-package :dmp) ; => :DOC-MP
(delete-package :doc-mp) ; => T
```
