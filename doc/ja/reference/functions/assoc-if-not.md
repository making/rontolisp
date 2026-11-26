# assoc-if-not

`(assoc-if-not predicate alist &key key)`

述語が car に対して偽を返した最初のペア。なければ nil を返します。述語を否定した [`assoc-if`](assoc-if.md) です。`:key` は述語に渡す前の car に適用されるセレクタです。規格では非推奨です。

```lisp
(assoc-if-not #'numberp '((1 . a) (b . c))) ; => (B . C)
```

```lisp
(assoc-if-not #'oddp '((1 . a) (2 . b)) :key #'1+) ; => (1 . A)
```
