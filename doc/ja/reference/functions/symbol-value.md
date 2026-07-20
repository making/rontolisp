# symbol-value

`(symbol-value symbol)`

`symbol` が指す**グローバル**変数の値を返します。未束縛の名前はエラーを通知します(WASM ではトラップ)。Common Lisp の dynamic 変数のみを見る `symbol-value` と同様、レキシカルな束縛は見えません。`t`・`nil`・キーワードは自分自身に評価されます。先に [`boundp`](boundp.md) で確認し、実行時に名前を組み立てるには [`intern`](intern.md) を使ってください。

```lisp
(defvar *level* 7)
(symbol-value '*level*) ; => 7
```

```lisp
(symbol-value (intern "*LEVEL*")) ; => 7
```

```lisp
(symbol-value :key) ; => :key
```

未束縛の変数はエラーを通知します:

```console
> (symbol-value '*nope*)
The variable *nope* is unbound
```
