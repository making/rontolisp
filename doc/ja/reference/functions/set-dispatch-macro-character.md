# set-dispatch-macro-character

`(set-dispatch-macro-character disp-char sub-char function &optional readtable)`

ライト版スタブ: 受け付けますが無視し、`t` を返します。ユーザーのディスパッチマクロで Java 側リーダーを拡張することはできません。

```lisp
(set-dispatch-macro-character #\# #\7 (lambda (s c n) nil)) ; => T
```
