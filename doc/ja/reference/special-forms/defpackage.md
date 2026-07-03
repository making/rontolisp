# defpackage

`(defpackage name (:use package...) (:export symbol...))`

`name` という名前の新しいパッケージを定義し、名前のシンボルを返します。`in-package` と同様に、read/コンパイル時に消費されるリテラルなトップレベルディレクティブであり、パッケージはソース順に定義されます。名前と clause の引数はキーワード、裸のシンボル、または文字列です(`:mypkg`、`mypkg`、`"mypkg"`)。

- `(:use package...)` は、列挙したパッケージの external(export 済み)シンボルを修飾なしで見えるようにします。使用するパッケージは先に存在していなければなりません。`:use` clause がない場合、修飾なしで見えるものは **何もありません** — 標準シンボルを `cl:` プレフィックスなしで使うには `(:use :cl)` と書きます。
- `(:export symbol...)` はパッケージの external シンボルを宣言します: 他のパッケージから `name:symbol` として参照でき、このパッケージを使用するパッケージに継承されます。後から intern されるシンボル(例えば `(in-package name)` の下で定義され `:export` clause に含まれない `defun`)は internal であり、ダブルコロン `name::symbol` が必要です。

既存パッケージの再定義はエラーで、上記以外の clause(`:nicknames`、`:shadow`、`:import-from`、`:documentation` など)もエラーです。完全なルールは[パッケージ](../packages.md#ユーザー定義パッケージdefpackage)を参照してください。

```lisp
(defpackage :util (:use :cl) (:export :trim)) ; => util
```

```lisp
(defpackage :mypkg (:use :cl) (:export :greet))
(in-package :mypkg)
(defun greet (name) (concatenate 'string "hello, " name))
(in-package :cl-user)
(mypkg:greet "world") ; => "hello, world"
```
