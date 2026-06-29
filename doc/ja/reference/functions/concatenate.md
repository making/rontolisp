# concatenate

`(concatenate result-type &rest strings)`

文字列引数を 1 つの新しい文字列に連結します。サポートされている結果型は `'string` のみで、コンパイル系バックエンドでは `result-type` をリテラルの `'string` として書く必要があります。文字列を 1 つも渡さない場合は空文字列 `""` を返します。

```lisp
(concatenate 'string "foo" "bar") ; => "foobar"
```
