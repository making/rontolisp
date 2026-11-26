# peek-char

`(peek-char [port])`

現在の入力ポートの次の文字を、消費せずに返します。したがって続く `read-char` は同じ文字を返します。入力の終わりではファイル終端オブジェクトを返します。`port` を渡すとそのポートから読みます。`port` は開いているテキスト入力ポートでなければなりません。

```stdin
xy
```

```scheme
(write (peek-char))
(write (peek-char))
(write (read-char))
(write (read-char))
(newline)
```

```
#\x#\x#\x#\y
```

```scheme
(let ((p (open-input-string "xy"))) (list (peek-char p) (read-char p) (read-char p))) ; => (#\x #\x #\y)
```
