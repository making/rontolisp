# setq

`(setq name value ...)`

変数 `name` に `value` を代入します。`value` は評価されますが `name` は評価されません。複数の `name value` の組を指定でき、それらは左から右へ代入されるため、後の `value` は前の代入結果を参照できます。最後の代入の値が返されます。`setq` は変数名前空間でのみ動作します(Lisp-2)。

```lisp
(let ((x 0)) (setq x 1 x (+ x 9)) x) ; => 10
```

レキシカルな束縛がなく `defvar` でも宣言されていない `name` は、`setq` がどこにあっても(関数本体の中でも)その名前のグローバル変数を指します。全バックエンドで同じで、SBCL も(警告を出したうえで)同じように扱います。宣言していない変数への代入は Common Lisp では未定義なので、移植性のある書き方は先に `defvar` か `defparameter` で宣言することです。

```lisp
(defun remember (v) (setq *last-seen* v))
(remember 42)
*last-seen* ; => 42
```

このようなグローバル変数は最初の代入まで未束縛です。それより前に読み出すと(たとえば先に呼んだ関数の中で読むと)、その名前を持つ `unbound-variable` が通知されます。すべてのバックエンドで SBCL と同じです。

```lisp
(defun total () (* *rate* 100))
(handler-case (total) (unbound-variable (e) (cell-error-name e))) ; => *RATE*
(setq *rate* 3)
(total) ; => 300
```
