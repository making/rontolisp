# listp

`(listp object)`

`object` がリスト (つまりコンスセルまたは空リスト `nil`) なら `t` を、そうでなければ `nil` を返します。`nil` はリストとみなされるため `(listp nil)` は `t` になり、ここが `listp` と `consp` の違いです。3 つすべてのバックエンドで動作します。

```lisp
(listp '(1 2)) ; => t
```

```lisp
(listp nil) ; => t
```
