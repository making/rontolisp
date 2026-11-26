# comment

`(comment expr...)`

何も評価せず `nil` を返します。何にも低レベル化されません。リーダーレベルの `;` 行コメントと `#_` 破棄については [Syntax](syntax.md) を見てください。

```clojure
(println (comment (undefined-thing 1))) ; nil
```
