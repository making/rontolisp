# list-all-packages

`(list-all-packages)`

登録されているすべてのパッケージを、[`find-package`](find-package.md) が返すのと同じキーワードのリストとして返します（rontolisp にパッケージオブジェクトはありません）。組み込みパッケージ、`keyword` 疑似パッケージ、プログラム中のすべての [`defpackage`](../special-forms/defpackage.md) が含まれます。

インタープリタは生きているレジストリを読みます。コンパイル済みバックエンドには実行時のレジストリがなく、コンパイル時に焼き込まれたテーブルから答えるため、コンパイル済みプログラムが後から作ったパッケージはそこからは見えません。

```lisp
(defpackage #:listed (:use #:cl))
(car (member :listed (list-all-packages))) ; => :LISTED
```
