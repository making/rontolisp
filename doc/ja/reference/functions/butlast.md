# butlast

`(butlast list)`

`list` の最後の要素を除いた新しいコピーを返します。元のリストは変更されません。空のリストや要素が 1 つだけのリストは `nil` を返します。フルの Common Lisp と異なり、rontolisp の `butlast` はリストのみを受け取り、オプションの個数引数はサポートしていません。

```lisp
(butlast '(1 2 3 4)) ; => (1 2 3)
```
