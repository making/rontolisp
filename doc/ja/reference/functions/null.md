# null

`(null object)`

`object` が空リスト `nil` であれば `t` を、そうでなければ `nil` を返します。`nil` が唯一の偽値であるため、これは論理的な偽の判定としても機能します。動作は `not` と同一です。3 つのバックエンドすべてで動作します。

```lisp
(null nil) ; => T
```

```lisp
(null '(1 2)) ; => NIL
```
