# torch:step-count

`(torch:step-count optimizer)`

このオプティマイザで [`torch:step`](torch-step.md) が実行された回数を返します。最初のステップの前は `0`、ステップの実行中は Adam のバイアス補正の `t` そのものです。カウンタはパラメータではなくオプティマイザが持つので、同じパラメータに対する 2 つのオプティマイザは別々のスケジュールを保ちます。

```lisp
(defparameter *opt* (torch:sgd (list (torch:parameter '(1.0))) :lr 0.1))
(torch:step-count *opt*) ; => 0
(torch:step *opt*)
(torch:step *opt*)
(torch:step-count *opt*) ; => 2
```
