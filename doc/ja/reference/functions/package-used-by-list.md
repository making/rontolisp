# package-used-by-list

`(package-used-by-list package)`

use リストに `package` を含むすべてのパッケージを、[`find-package`](find-package.md) が返すのと同じキーワードのリストとして返します -- [`package-use-list`](package-use-list.md) の逆向きです。存在しない designator はエラーです。

インタープリタは生きているレジストリを、コンパイル済みバックエンドは `package-use-list` と同じコンパイル時テーブルを読みます。

```lisp
(defpackage #:provider (:use #:cl) (:export #:thing))
(defpackage #:consumer (:use #:cl #:provider))
(package-used-by-list '#:provider) ; => (:CONSUMER)
```
