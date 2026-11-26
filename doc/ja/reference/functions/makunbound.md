# makunbound

`(makunbound symbol)`

`symbol` の現在の値束縛を未束縛にし、`symbol` を返します。変数の動的束縛(`let`、引数、`progv`)の内側ではその束縛が値を失い、束縛が終わると以前の値に戻ります。それ以外ではグローバルな値が削除されます。以降 [`boundp`](boundp.md) は `nil` を返し、変数の読み出し(直接、[`symbol-value`](symbol-value.md) 経由、`eval` 経由)は `unbound-variable` を通知します。後から代入すれば再び値を持ちます。

```lisp
(defvar *level* 1)
(list (makunbound '*level*) (boundp '*level*)
      (handler-case *level* (unbound-variable () :unbound))) ; => (*LEVEL* NIL :UNBOUND)
```

コンパイルバックエンドではすべてのスペシャル変数が対象です。スペシャル変数でないグローバルは `boundp` と `eval` に対してのみ未束縛になり、コンパイル済みの読み出しは値を返し続けます。
