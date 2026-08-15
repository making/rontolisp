# package-use-list

`(package-use-list package)`

`package` が use しているパッケージを、[`find-package`](find-package.md) が返すのと同じキーワードのリストとして返します（rontolisp にパッケージオブジェクトはありません）。引数はパッケージ designator であれば何でも構いません -- キーワード、文字列、シンボル、パッケージ値。存在しないパッケージはエラーです。逆向きは [`package-used-by-list`](package-used-by-list.md) です。

インタープリタは生きているレジストリを読みます。コンパイル済みバックエンドには実行時のレジストリがなく、コンパイル時に焼き込まれたテーブルから答えるため、コンパイル済みプログラムが後から作ったパッケージはそこからは見えません。

```lisp
(defpackage #:uses-cl (:use #:cl))
(package-use-list '#:uses-cl) ; => (:CL)
```
