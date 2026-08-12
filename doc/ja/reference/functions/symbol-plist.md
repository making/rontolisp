# symbol-plist

`(symbol-plist symbol)`

シンボルの属性リスト全体 — [`get`](get.md) が引く指標と値の対 — を返します。何もなければ `nil` です。シンボルは名前で比較され plist を保持する実体セルを持たないため、リストは `get` が書き込むのと同じプログラム全体で 1 つの名前キーのストアから得られます。`(setf symbol-plist)` はありません。

```lisp
(symbol-plist 'no-props) ; => NIL
```

```lisp
(setf (get 'my-sym 'color) :red)
(symbol-plist 'my-sym) ; => (COLOR :RED)
```
