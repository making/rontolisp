# consp

`(consp object)`

`object` がコンスセルであれば `t` を、そうでなければ `nil` を返します。空リスト `nil` はコンスセルではないため、`(consp nil)` は `nil` になります。これが `consp` と `listp` の違いです。`atom` の正確な補集合です。3 つすべてのバックエンドで動作します。

```lisp
(consp '(1 2)) ; => t
```

```lisp
(consp nil) ; => nil
```
