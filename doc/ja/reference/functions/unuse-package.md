# unuse-package

`(unuse-package packages &optional package)`

[`use-package`](use-package.md) の逆操作です。`packages`（パッケージ designator またはそのリスト）を `package`（既定では現在のパッケージ）の use リストから取り除き、その外部シンボルが修飾なしでは見えないようにします。`t` を返します。use していないパッケージを unuse するのは何もしないのと同じで、存在しないパッケージはエラーです（`No such package: NOSUCH`）。

`use-package` とまったく同じようにコンパイル時に消費されるため、リテラルなトップレベル呼び出しはそれ以降のフォームに効果を及ぼし、すべてのバックエンドで動作します。実行時に計算される呼び出しはインタープリタでは生きた use リストを書き換えますが、コンパイル済みバックエンドではレジストリが凍結されているため、引数を評価して `t` を返すだけで何も変更しません -- [`export`](export.md) と同じ規則です。

```lisp
(defpackage #:toolbox (:use #:cl) (:export #:widget))
(in-package #:toolbox)
(defun widget () 7)
(in-package #:cl-user)
(use-package '#:toolbox)
(widget) ; => 7
(unuse-package '#:toolbox)
(toolbox:widget) ; => 7
```
