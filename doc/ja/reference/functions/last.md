# last

`(last list)`

`list` の最後のコンスセル、すなわち最終要素を含む 1 要素のリストを返します。空のリストでは `nil` を返します。完全な Common Lisp とは異なり、rontolisp の `last` はリストのみを取ります。オプションのカウント引数 (`(last list n)`) はサポートされていません。

```lisp
(last '(1 2 3)) ; => (3)
```
