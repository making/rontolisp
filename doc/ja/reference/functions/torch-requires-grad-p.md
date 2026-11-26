# torch:requires-grad-p

`(torch:requires-grad-p tensor)`

テンソルが自動微分に参加するかどうかを返します。`:requires-grad t` で作られた葉、または ([`torch:no-grad`](../macros/torch-no-grad.md) の外で) 参加するテンソルから計算されてテープに記録された結果が該当します。

```lisp
(defparameter *w* (torch:tensor '(1.0) :requires-grad t))
(torch:requires-grad-p *w*)                  ; => T
(torch:requires-grad-p (torch:mul *w* 2.0))  ; => T
(torch:requires-grad-p (torch:tensor '(1.0))) ; => NIL
```
