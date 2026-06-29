# substitute

`(substitute new old list)`

`old` に `eql` であるすべての要素を `new` で置き換えた新しいリストを返します。それ以外の要素は変更されません。引数は位置指定のみで、`:test` や `:key` はありません。元のリストは変更されません。破壊的なバージョンには `nsubstitute` を使用してください。

```lisp
(substitute 0 2 '(1 2 3 2)) ; => (1 0 3 0)
```
