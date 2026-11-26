# member-if-not

`(member-if-not predicate list &key key)`

述語が偽を返した最初の要素から始まる `list` の末尾。なければ nil を返します。述語を否定した [`member-if`](member-if.md) です。`:key` は述語に渡す前の各要素に適用されるセレクタです。規格では非推奨です。

```lisp
(member-if-not #'numberp '(1 2 a b)) ; => (A B)
```

```lisp
(member-if-not #'evenp '(1 3 4 5) :key #'1+) ; => (4 5)
```
