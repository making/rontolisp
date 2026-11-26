# simple-condition-format-arguments

`(simple-condition-format-arguments condition)`

コンディションインスタンスの `:format-arguments` スロットを返します (スロットがない場合は nil)。

```lisp
(simple-condition-format-arguments
 (make-condition 'simple-error :format-control "boom ~a" :format-arguments '(1))) ; => (1)
```
