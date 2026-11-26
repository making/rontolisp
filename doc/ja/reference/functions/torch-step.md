# torch:step

`(torch:step optimizer)`

オプティマイザの更新則をすべてのパラメータに適用し、オプティマイザを返します (PyTorch の `optimizer.step()`)。ステップカウンタは**先に**加算されるので、[`torch:step-count`](torch-step-count.md) を読むバイアス補正は最初のステップで `1` を見ます。

更新は torch の演算を使わず各パラメータのデータを直接書き換えるため、テープには何も記録されません。[`torch:set-data`](torch-set-data.md) で手書きした更新と違い、[`torch:no-grad`](../macros/torch-no-grad.md) で囲む必要はありません。勾配がまだ `NIL` のパラメータは飛ばされます。

```lisp
(defparameter *p* (torch:parameter '(4.0)))
(defparameter *opt* (torch:sgd (list *p*) :lr 0.5))
(torch:backward (torch:sum (torch:mul *p* *p*)))
(torch:step *opt*)
(torch:data *p*)         ; => #f(0.0)
(torch:step-count *opt*) ; => 1
```
