# boundp

`(boundp symbol)`

`symbol` が束縛された**グローバル**変数(`defvar`/`defparameter`/トップレベルの `setq`)を指すとき `t` を、そうでなければ nil を返します。Common Lisp の dynamic 変数のみを見る `boundp` と同様、レキシカルな束縛(`let`、関数引数)は見えません。`t`・`nil`・キーワードは自己評価する定数なので、それらの `boundp` は `t` です。

コンパイルバックエンドでは、この判定は埋め込まれた eval ランタイムのグローバル環境を読むため、`boundp`(や [`symbol-value`](symbol-value.md)/[`fboundp`](fboundp.md))を使うと `eval` と同様にそのランタイムが出力に含まれます。

```lisp
(defvar *level* 7)
(boundp '*level*) ; => T
```

```lisp
(boundp '*undefined-var*) ; => NIL
```

```lisp
(boundp :key) ; => T
```

```lisp
(let ((x 1)) (boundp 'x)) ; => NIL
```
