# define-symbol-macro

`(define-symbol-macro name expansion)`

グローバルなシンボルマクロを定義します。これ以降、値の位置での `name` への参照はその場で `expansion` を評価し、`name` への `setq`/`setf` は `expansion` を `setf` の place として代入します。[`symbol-macrolet`](../macros/symbol-macrolet.md) のトップレベル版で、置換規則も同じです——クオートされたデータ、関数名前空間の位置（`#'name`、呼び出しのヘッド）、`case` 系のキー、`go` タグ、`block` 名は置換されず、同名の内側の束縛（`let`、`lambda` のパラメータ、`dolist` など）はそのスコープでシャドウします。`name` は変数ではありません。何もそれを束縛せず、`expansion` の形は参照のたびに再評価されます。名前を返します。

定義は**トップレベル**のフォームでなければならず（`progn` や `eval-when` で包まれていても構いません。`cffi:defcvar` が展開する形がこれです）、`name` はリテラルのシンボルでなければなりません。コンパイラはプログラムを読む時点で定義を解決するため、定義より前のフォームからの参照はそれを見ません。

```lisp
(defvar *buf* (make-array 3 :initial-element 0))
(define-symbol-macro slot0 (aref *buf* 0))
(setf slot0 42)
(incf slot0)
(list slot0 *buf*) ; => (43 #(43 0 0))
```
