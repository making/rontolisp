# boundp

`(boundp symbol)`

`symbol` が束縛された**グローバル**変数(`defvar`/`defparameter`/トップレベルの `setq`)を指すとき `t` を、そうでなければ nil を返します。Common Lisp の dynamic 変数のみを見る `boundp` と同様、レキシカルな束縛(`let`、関数引数)は見えません。`t`・`nil`・キーワードは自己評価する定数なので、それらの `boundp` は `t` です。

コンパイルバックエンドでは、シンボルが**リテラル**の `boundp` はコンパイル時に答えが決まり、コストはゼロです。コンパイル済みプログラムは実行時に新しいグローバル変数を生やせないため、その呼び出しより前の定義だけで答えが決まります。計算されたシンボル(`(boundp (intern name))`)の場合のみ、埋め込まれた eval ランタイムのグローバル環境を読むため、`eval` と同様にそのランタイムが出力に含まれます — [`symbol-value`](symbol-value.md) と [`fboundp`](fboundp.md) は引数によらず常にそうなります。`--dynamic` でコンパイルした場合、あるいは `eval`/`load` を呼ぶプログラムでは、実行時判定のまま残ります。

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
