# use-package

`(use-package packages &optional package)`

`packages`（パッケージ designator またはそのリスト）を `package`（省略時は現在のパッケージ）の use リストに追加し、以降、使用されたパッケージの**外部**シンボルが修飾なしで見えるようにします。`t` を返します。同じパッケージを二度 use するのは何もしないのと同じで、自分自身を use するのはエラー、存在しないパッケージもエラーです（`No such package: NOSUCH`）。[`defpackage`](../special-forms/defpackage.md) の `:use` 節の実行時版です。

rontolisp ではパッケージは読み込み/コンパイル時に解決されるため（[パッケージ](../packages.md)を参照）、リテラルなトップレベル呼び出しは `in-package` と同様にコンパイル時に消費され、それ以降のフォームに効果を及ぼします -- これがすべてのバックエンドで動作する理由です。実行時に計算される呼び出し（実行時に組み立てた designator）はインタープリタのみで動作します。内部シンボルは決して継承されません。使用されるパッケージが `:export` したものだけが見えるようになります。

```lisp
(defpackage #:greeter (:use #:cl) (:export #:hello))
(in-package #:greeter)
(defun hello () "hi")
(in-package #:cl-user)
(use-package '#:greeter)
(hello) ; => "hi"
```
