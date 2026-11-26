# caar cddddr

`(cadr list)`、`(caddr list)`、... -- 2 段から 4 段までのすべての `c{a,d}+r` 名。

各複合アクセサは、右から左に読む `car`/`cdr` 操作の並びを適用します。`cadr` は `(car (cdr x))`、`caddr` は `(car (cdr (cdr x)))`、`cddr` は `(cdr (cdr x))` といった具合で、`cddddr` までの 2 段・3 段・4 段のすべての組み合わせが用意されています。`car`/`cdr` と同様に、途中で `nil` に適用してもエラーにならず `nil` を返します。

```lisp
(caddr '(1 2 3 4)) ; => 3
```

```lisp
(cddr '(1 2 3 4)) ; => (3 4)
```
