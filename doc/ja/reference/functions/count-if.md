# count-if

`(count-if predicate list)`

`list` の中で `predicate` を満たす要素の個数を返します。これは `count` の述語ベース版です。

```lisp
(count-if #'evenp '(1 2 3 4)) ; => 2
```
