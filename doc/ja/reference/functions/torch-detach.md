# torch:detach

`(torch:detach tensor)`

データを共有しつつ自動微分テープから切り離した新しい葉テンソルを返します。`requires-grad` も記録された履歴も持たないため、これ以降の計算から勾配は流れません。ブロック単位の同等物は [`torch:no-grad`](../macros/torch-no-grad.md) マクロです。

```lisp
(defparameter *w* (torch:tensor '(1.0 2.0) :requires-grad t))
(defparameter *y* (torch:mul *w* 3.0))
(torch:requires-grad-p *y*)                ; => T
(torch:requires-grad-p (torch:detach *y*)) ; => NIL
```
