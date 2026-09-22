# unintern

`(unintern symbol &optional package)`

`symbol` を `package`(省略時はカレントパッケージ)のメンバーテーブルから取り除きます。以降 [`find-symbol`](find-symbol.md) はそれをパッケージ自身のシンボルとしては返さず、そのパッケージがシンボルのホームだった場合は [`symbol-package`](symbol-package.md) が `nil` を返すようになります。インポートされたシンボルはリダイレクトを失うだけです。いずれの場合も外部ではなくなり、シャドーイングでもなくなります。シンボルが存在していれば `t` を、存在していなければ(継承しているだけのシンボルや、他所がホームでインポートされていないシンボルなら)`nil` を返します。同名の異なる 2 つの継承シンボルを隠していたシャドーイングシンボルを取り除くと名前衝突が残るため、その場合は `package-error` を通知し、何も変えません。

Common Lisp との相違: ここではシンボルはその綴りなので、同じ名前を再びそのパッケージにインターンすると古いシンボルが再びホームを得ます(Common Lisp は別のシンボルを作ります)。コンパイル系バックエンドでは、除去はプログラムが [`make-package`](make-package.md) で作ったパッケージについて記録され(読み込み/コンパイル時パッケージはそこでは凍結されており、`unintern` は `nil` を返します)、`symbol-package` は引き続き綴りの修飾子を読みます。

```lisp
(make-package :un-demo :use nil)
(defvar *un-sym* (intern "TEMP" :un-demo))
(unintern *un-sym* :un-demo) ; => T
(symbol-package *un-sym*) ; => NIL
(find-symbol "TEMP" :un-demo) ; => NIL
(unintern *un-sym* :un-demo) ; => NIL
```
