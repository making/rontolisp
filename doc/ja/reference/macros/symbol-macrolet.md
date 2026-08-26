# symbol-macrolet

`(symbol-macrolet ((name expansion)...) body...)`

本体に対してレキシカルスコープの局所シンボルマクロを定義します。本体中の各 `name` への自由参照はその位置で `expansion` を評価し、`name` への `setq`/`setf` は展開形を `setf` の place として代入します。同名の内側の束縛（`let`/`let*`、`lambda`/`defun` のパラメータ、`do`、`dolist` など）はそのスコープでシンボルマクロをシャドウします。クオートされたデータ、関数名前空間の位置（`#'name`、呼び出しのヘッド）、`case` 系のキー、`go` タグ、`block` 名は置換されず、本体直下の宣言は除去されます。兄弟マクロは互いの展開形を参照できます。自己参照する展開形は一度だけ置換され、再帰的には展開されません。グローバル版は [`define-symbol-macro`](../special-forms/define-symbol-macro.md) です。

```lisp
(let ((cell (list 1 2)))
  (symbol-macrolet ((head (car cell)))
    (setf head 99)
    cell)) ; => (99 2)
```

```lisp
(symbol-macrolet ((x 42))
  (list (let ((x 1)) x) x)) ; => (1 42)
```
