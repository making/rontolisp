# open-input-string

`(open-input-string string)`

`string` の文字を読むテキスト入力ポートを返します。ポートはそれぞれ読み取り位置、押し戻した文字、`#!fold-case` の状態を持つので、複数のポートを交互に読めます。

```scheme
(read (open-input-string "(a . b) c")) ; => (a . b)
```
