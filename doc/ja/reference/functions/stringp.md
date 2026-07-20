# stringp

`(stringp object)`

`object` が文字列であれば `t` を、そうでなければ `nil` を返します。シンボルは文字列ではないため、`(stringp 'hello)` は `nil` です。3 つすべてのバックエンドで動作します。

```lisp
(stringp "hello") ; => T
```

```lisp
(stringp 'hello) ; => NIL
```
