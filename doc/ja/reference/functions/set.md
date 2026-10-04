# set

`(set symbol value)`

`symbol` が指す変数に `value` を設定します。`setq` の計算名版です。名前の動的束縛(`let`、引数、`progv` によるもの)が有効ならその束縛に設定され、束縛のエクステントが終われば通常どおり元に戻ります。有効な束縛がなければグローバル変数に設定し、未束縛の名前は束縛を作成します。名前が新しい可能性がある場合は先に [`boundp`](boundp.md) で確認し、実行時に名前を組み立てるには [`intern`](intern.md) を使ってください。`(setf (symbol-value symbol) value)` は同じ格納です。`nil`・`t`・キーワードなどの定数は設定できず、シンボル以外はエラーを通知します。

```lisp
(defvar *level* 7)
(set '*level* 8)
*level* ; => 8
```

```lisp
(set (intern "*BONUS*") 3)
(symbol-value '*bonus*) ; => 3
```

```lisp
(setf (symbol-value '*level*) 9)
*level* ; => 9
```

```lisp
(defun bump (name) (set name 99))
(let ((*level* 1))
  (bump '*level*)
  *level*) ; => 99
*level* ; => 9
```
