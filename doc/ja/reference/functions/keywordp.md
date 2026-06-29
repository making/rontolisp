# keywordp

`(keywordp object)`

`object` がキーワード (`:foo` のように先頭にコロンを付けて書かれたシンボル) なら `t` を、そうでなければ `nil` を返します。通常のシンボルはキーワードではないため、`(keywordp 'foo)` は `nil` です。3 つすべてのバックエンドで動作します。

```lisp
(keywordp :foo) ; => t
```

```lisp
(keywordp 'foo) ; => nil
```
