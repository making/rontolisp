# set

`(set symbol value)`

`symbol` が指す**グローバル**変数に `value` を設定し、未束縛の名前は束縛を作成します。`setq` の計算名版です。名前が新しい可能性がある場合は先に [`boundp`](boundp.md) で確認し、実行時に名前を組み立てるには [`intern`](intern.md) を使ってください。`(setf (symbol-value symbol) value)` は同じ格納です。既に有効な動的束縛はすべてのバックエンドで一様に変更されません。`set` はグローバルな名前空間を対象とし、現在の動的束縛への代入には `setq` を使ってください。`nil`・`t`・キーワードなどの定数は設定できず、シンボル以外はエラーを通知します。

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
