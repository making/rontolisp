# characterp

`(characterp object)`

`object` が文字であれば `t` を、そうでなければ `nil` を返します。文字リテラルは `#\` プレフィックスで書きます（例: `#\a`）。1 文字の文字列は文字ではないため、`(characterp "a")` は `nil` になります。3 つすべてのバックエンドで動作します。

```lisp
(characterp #\a) ; => t
```

```lisp
(characterp "a") ; => nil
```
