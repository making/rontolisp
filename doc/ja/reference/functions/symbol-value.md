# symbol-value

`(symbol-value symbol)`

`symbol` が指す**動的**(グローバル)変数の現在の値を返します。動的束縛 — スペシャル変数の `let`、同名の関数引数、`progv` — が有効ならその最も内側の束縛の値を、なければグローバルの値を返します。未束縛の名前は、その名前を持つ `unbound-variable` を通知します([`cell-error-name`](cell-error-name.md) を参照。WASM では捕捉するフォームを持たないプログラムはトラップします)。Common Lisp の `symbol-value` と同様、レキシカルな束縛は見えません。コンパイルバックエンドでは、値なしで宣言したスペシャル変数(`(defvar *x*)`)は、その変数の参照と同じくエラーを通知せず `nil` を返します。`t`・`nil`・キーワードは自分自身に評価されます。先に [`boundp`](boundp.md) で確認し、実行時に名前を組み立てるには [`intern`](intern.md) を使ってください。

```lisp
(defvar *level* 7)
(symbol-value '*level*) ; => 7
```

```lisp
(symbol-value (intern "*LEVEL*")) ; => 7
```

```lisp
(symbol-value :key) ; => :KEY
```

未束縛の変数はエラーを通知します:

```console
CL-USER> (symbol-value '*nope*)
The variable *nope* is unbound
```
