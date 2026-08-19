# torch:grad

`(torch:grad tensor)`

[`torch:backward`](torch-backward.md) がこのテンソルに蓄積した勾配 (データと同じ形の生の linalg 値) を返します。まだ backward が到達していなければ `nil` です。勾配は backward の呼び出しをまたいで蓄積 (`+=`) され、[`torch:zero-grad`](torch-zero-grad.md) でクリアします。

```lisp
(defparameter *w* (torch:tensor '(1.0 2.0) :requires-grad t))
(torch:grad *w*)                                   ; => NIL
(torch:backward (torch:sum (torch:mul *w* *w*)))
(torch:grad *w*)                                   ; => #d(2.0 4.0)
```
