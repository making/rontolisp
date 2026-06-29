# find

`(find item list)`

`item` と `eql` である `list` の最初の要素を返します。一致する要素がなければ `nil` を返します。一致した末尾を返す `member` とは異なり、`find` は要素そのものを返します。インデックスを得たい場合は代わりに `position` を使います。

```lisp
(find 2 '(1 2 3)) ; => 2
```
