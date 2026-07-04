# find

`(find item sequence)`

`item` と `eql` である `sequence` の最初の要素を返します。一致する要素がなければ `nil` を返します。シーケンスにはリストまたは文字列を渡せます。文字列の要素は文字です。一致した末尾を返す `member` とは異なり、`find` は要素そのものを返します。インデックスを得たい場合は代わりに `position` を使います。

```lisp
(find 2 '(1 2 3)) ; => 2
```

```lisp
(find #\l "hello") ; => #\l
```
