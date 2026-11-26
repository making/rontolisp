# store-value

`(store-value value [condition])`

最内のアクティブな `store-value` リスタートを `value` を渡して起動します。アクティブなものがなければ `nil` を返します — [`use-value`](use-value.md) の対になる関数です(CL ではこの 2 つが対をなします: `use-value` は一度きりの代替値を渡し、`store-value` はシグナル側にその値の保存も求めます)。

```lisp
(handler-bind ((error (lambda (c) (store-value 7))))
  (restart-case (error "no value")
    (store-value (v) (list :stored v)))) ; => (:STORED 7)
```
