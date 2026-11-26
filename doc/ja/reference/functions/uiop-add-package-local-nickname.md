# uiop:add-package-local-nickname

`(uiop:add-package-local-nickname nickname package &optional (scope-package *package*))`

`scope-package` が `package` を呼ぶための自分専用の名前を定義し、`scope-package` を返します。`scope-package` がカレントのとき、`nickname:symbol` は `package:symbol` として読まれ、パッケージ指示子 `nickname` は `package` を指し、`package` のシンボルは `nickname:symbol` と印字されます。長いパッケージ名を短縮するためにライブラリが推奨するイディオムです（例: jzon の `(uiop:add-package-local-nickname '#:jzon '#:com.inuoe.jzon)`）。[`defpackage`](../special-forms/defpackage.md) の `:local-nicknames` 節はパッケージ定義時に同じことを行います。

同じパッケージに対してニックネームをもう一度追加しても何も起きません。`scope-package` が既に持つニックネームに別のパッケージを与えると `package-error` をシグナルします。`cl`、`common-lisp`、`keyword` と `scope-package` 自身の名前も同様です。

[`uiop:package-local-nicknames`](../uiop.md) はパッケージのニックネームを `("NICKNAME" . package)` の組のリストとしてニックネーム順に返します。`uiop:remove-package-local-nickname` はニックネームを取り除き、パッケージがそれを持っていたときは `t`、持っていなかったときは `nil` を返します。

リテラルなトップレベルの呼び出しは、すべてのバックエンドで、それ以降に読まれるフォームに対して有効です。計算された呼び出しは実行時に有効になります。コンパイルパスはプログラム全体を読んでから実行するので、計算された呼び出しのニックネームが見えるのは実行時にパッケージを探す処理(`find-package`、`intern`、`read`、プリンタ)だけで、ソースに書かれた `nickname:symbol` には適用されません。

パッケージの値はその名前のキーワードです。そのため、カレントパッケージのニックネームと同じ名前のパッケージをパッケージ指示子として関数に渡すと、ニックネームとして読まれます。

```lisp
(defpackage #:com.example.deeply.nested (:use #:cl) (:export #:answer))
(in-package #:com.example.deeply.nested)
(defun answer () 42)
(in-package #:cl-user)
(uiop:add-package-local-nickname '#:nick '#:com.example.deeply.nested)
(nick:answer) ; => 42
```
