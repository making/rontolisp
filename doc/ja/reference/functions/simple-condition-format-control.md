# simple-condition-format-control

`(simple-condition-format-control condition)`

コンディションインスタンスの `:format-control` スロットを返します (スロットがない場合は nil)。[`simple-condition-format-arguments`](simple-condition-format-arguments.md) と組み合わせて、`:report` 関数がラップしたコンディションのメッセージを再構成できます。

```lisp
(simple-condition-format-control
 (make-condition 'simple-error :format-control "boom ~a")) ; => "boom ~a"
```
