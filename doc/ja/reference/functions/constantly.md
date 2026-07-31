# constantly

`(constantly value)`

引数を何個受け取っても常に `value` を返す関数を返します。「すべて受け入れる」引数の
定石で、`uiop:collect-sub*directories` は 2 つ取ります。

```lisp
(mapcar (constantly 7) '(a b c))   ; => (7 7 7)
```

## バックエンドサポート

4 バックエンドすべてです。rontolisp ソースで 1 つだけ定義されているので、`complement`
（ラップするラムダへ展開されます）と違って本物の関数であり、`#'constantly` も使えます。
