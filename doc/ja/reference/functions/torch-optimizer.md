# torch:optimizer

`(torch:optimizer kind params fields step-fn)`

新しいオプティマイザを返します。`kind` は更新則を表すキーワード、`params` はモジュール ([`torch:parameters`](torch-parameters.md) をたどります) またはパラメータテンソルのリスト、`fields` はハイパーパラメータや内部状態を保持する `KEYWORD`/値の plist、`step-fn` は [`torch:step`](torch-step.md) が `(funcall step-fn optimizer)` として呼ぶ関数です。ステップカウンタは `0` から始まります。

ユーザー定義の更新則はこの形で書きます。[`torch:sgd`](torch-sgd.md) と [`torch:adam`](torch-adam.md) も単にこれを呼ぶだけです。ステップ関数はハイパーパラメータを [`torch:field`](torch-field.md) で、パラメータを [`torch:optimizer-params`](torch-optimizer-params.md) で読み戻すので、更新則が必要とするものはクロージャではなくレコードの中に置かれます。

```lisp
(defun scaled-step (self)
  (dolist (p (torch:optimizer-params self))
    (unless (null (torch:grad p))
      (torch:set-data p (linalg:sub (torch:data p)
                                    (linalg:mul (torch:field self :lr) (torch:grad p)))))))
(defparameter *p* (torch:parameter '(1.0 2.0)))
(defparameter *opt* (torch:optimizer :my-sgd (list *p*) (list :lr 0.5) (function scaled-step)))
(torch:backward (torch:sum (torch:mul *p* *p*)))
(torch:step *opt*)
(torch:data *p*)             ; => #d(0.0 0.0)
(torch:optimizer-kind *opt*) ; => :MY-SGD
```
