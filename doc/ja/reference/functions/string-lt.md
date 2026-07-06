# string<

`(string< string1 string2)`

大文字小文字を区別する辞書順比較です。`string1` が `string2` より厳密に前に並ぶ場合は最初に異なる文字位置のインデックスを、そうでない場合は `nil` を返します。各引数は `string` で強制変換されるため、シンボル指定子も受け付けます。

```lisp
(string< "abc" "abd") ; => 2
```
