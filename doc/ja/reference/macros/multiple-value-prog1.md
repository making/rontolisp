# multiple-value-prog1

`(multiple-value-prog1 first-form form...)`

`first-form` を評価し、続いて残りのフォームを副作用のために評価した後、`first-form` の**すべての値**を返します。[`prog1`](prog1.md) を多値に拡張したものです。値は [`multiple-value-list`](multiple-value-list.md) で捕捉され [`values-list`](values-list.md) で再公開されるため、間に挟まるフォームが何をしても失われません。

```lisp
(multiple-value-list
  (multiple-value-prog1 (floor 17 5) (list :cleanup))) ; => (3 2)
```
