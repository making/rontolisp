# get-dispatch-macro-character

`(get-dispatch-macro-character disp-char sub-char &optional (readtable *readtable*))`

ディスパッチマクロ文字 `disp-char` の下の `sub-char` のリーダー関数です。なければ `nil` を返します(10進数字には常にありません)。`disp-char` がディスパッチマクロ文字でなければエラーを通知します。標準のサブ文字は標準の関数を返し、それはどのリードテーブルでも同じオブジェクトです。4つのバックエンドすべてで動作します。

```lisp
(list (functionp (get-dispatch-macro-character #\# #\')) (get-dispatch-macro-character #\# #\0))
; => (T NIL)
```
