# nunion

`(nunion list-1 list-2 &key test test-not key)`

2 つのリストの和集合。[`union`](union.md) と同じです。CL は `nunion` が引数のセルを再利用することを許しますが、rontolisp は規格が認める非破壊的な結果を返すため、引数は変更されません。結果の順序は未規定です。

```lisp
(nunion (list 1 2 3) (list 2 3 4)) ; => (4 1 2 3)
```

```lisp
(nunion (list "a") (list "A") :test #'string-equal) ; => ("a")
```
