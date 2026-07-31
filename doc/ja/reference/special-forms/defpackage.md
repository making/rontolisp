# defpackage

`(defpackage name (:use package...) (:export symbol...) (:nicknames name...) (:import-from package symbol...) (:local-nicknames (nick actual)...))`

`name` という名前の新しいパッケージを定義し、名前のシンボルを返します。`in-package` と同様に、read/コンパイル時に消費されるリテラルなトップレベルディレクティブであり、パッケージはソース順に定義されます。名前と clause の引数はキーワード、裸のシンボル、文字列、または uninterned シンボルです(`:mypkg`、`mypkg`、`"mypkg"`、`#:mypkg` — 最後がポータブルな defpackage の慣用形です)。

- `(:use package...)` は、列挙したパッケージの external(export 済み)シンボルを修飾なしで見えるようにします。使用するパッケージは先に存在していなければなりません。`:use` clause がない場合、修飾なしで見えるものは **何もありません** — 標準シンボルを `cl:` プレフィックスなしで使うには `(:use :cl)` と書きます。`common-lisp` と `common-lisp-user` は `cl` と `cl-user` の組み込みニックネームなので、`(:use #:common-lisp)` も動作します。
- `(:export symbol...)` はパッケージの external シンボルを宣言します: 他のパッケージから `name:symbol` として参照でき、このパッケージを使用するパッケージに継承されます。後から intern されるシンボル(例えば `(in-package name)` の下で定義され `:export` clause に含まれない `defun`)は internal であり、ダブルコロン `name::symbol` が必要です。
- `(:nicknames name...)` はパッケージの別名を登録します。ニックネームは正規名が解決されるすべての場所で解決されます。既存のパッケージ(またはニックネーム)と衝突するニックネームはエラーです。組み込みニックネーム — `common-lisp` と `common-lisp-user`(`cl`/`cl-user`)、`rl`(`rontolisp`)、`la`(`linalg`)、`quicklisp`(`ql`)— も組み込みパッケージ名と同様に予約されています。
- `(:import-from package symbol...)` は、パッケージ全体を use せずに `package` の指定シンボルだけを修飾なしで見えるようにします。解決はテキストベースです: import された名前はソースパッケージの正規表記に解決されるので、`(:import-from #:common-lisp #:car)` は何も use しないパッケージに `car` だけを与えます。
- `(:local-nicknames (nick actual)...)` は**別の**パッケージの短縮名を登録し、`nick:symbol` が `actual:symbol` と同様に解決されます — ライブラリが長いパッケージ名を短縮するのに使うイディオムです。lite 版: ニックネームは**グローバル**です（rontolisp にパッケージごとのニックネームスコープはありません）。衝突規則は `:nicknames` と同じです。`defpackage` の外では [`uiop:add-package-local-nickname`](../functions/uiop-add-package-local-nickname.md) で同じ登録ができます。
- `(:documentation "...")` と `(:size n)` は受理されますが無視されます。

既存パッケージの再定義はエラーで、`:shadow`/`:shadowing-import-from`(rontolisp にシンボルのシャドウイングはありません)およびその他の clause(`:intern` など)もエラーです。完全なルールは[パッケージ](../packages.md#ユーザー定義パッケージdefpackage)を参照してください。

```lisp
(defpackage :util (:use :cl) (:export :trim)) ; => util
```

```lisp
(defpackage #:mypkg
  (:use #:common-lisp)
  (:nicknames #:mp)
  (:import-from #:rontolisp #:version)
  (:export #:greet))
(in-package :mypkg)
(defun greet (name) (concatenate 'string "hello, " name))
(in-package :cl-user)
(mp:greet "world") ; => "hello, world"
```
