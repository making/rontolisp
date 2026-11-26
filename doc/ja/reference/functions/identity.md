# identity

`(identity object)`

`object` をそのまま返します。`mapcar`、`find-if`、`sort` といった高階演算子に対して、何もしない変換やキーが必要な場合のデフォルトやプレースホルダの関数引数として便利です。

```lisp
(identity 42) ; => 42
```
